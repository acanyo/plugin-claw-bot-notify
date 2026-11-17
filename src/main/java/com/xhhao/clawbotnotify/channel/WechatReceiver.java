package com.xhhao.clawbotnotify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.command.InboundCommandService;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Acknowledges encrypted receive batches only after durable read-command capture. */
@Component
public class WechatReceiver {
    private static final Logger log = LoggerFactory.getLogger(WechatReceiver.class);
    private final BindingRepository bindings;
    private final CredentialStore credentials;
    private final WechatProtocolClient channel;
    private final InboundCommandService commands;
    private final ObjectMapper json = new ObjectMapper();

    public WechatReceiver(
            BindingRepository bindings,
            CredentialStore credentials,
            WechatProtocolClient channel,
            InboundCommandService commands) {
        this.bindings = bindings;
        this.credentials = credentials;
        this.channel = channel;
        this.commands = commands;
    }

    public Mono<Void> tick(BooleanSupplier active) {
        return bindings.current()
                .filter(binding -> active.getAsBoolean() && binding.getSpec().isEnabled())
                .flatMap(binding -> {
                    var spec = binding.getSpec();
                    return credentials
                            .read(spec.getCredentialRef())
                            .flatMap(values -> drain(binding, values))
                            .then(credentials.read(spec.getCredentialRef()))
                            .flatMap(values ->
                                    channel.poll(spec.getApiBaseUrl(), values.get("botToken"), values.get("cursor")))
                            .flatMap(batch -> {
                                if (!active.getAsBoolean()) return Mono.empty();
                                // Batch and next cursor are committed together in one encrypted Secret update.
                                // A restart drains pendingBatch before requesting another batch.
                                return credentials
                                        .update(spec.getCredentialRef(), values -> {
                                            values.put("pendingBatch", batch.toString());
                                            values.put(
                                                    "cursor",
                                                    batch.path("get_updates_buf")
                                                            .asText());
                                        })
                                        .then(credentials.read(spec.getCredentialRef()))
                                        .flatMap(values -> drain(binding, values));
                            })
                            .then(bindings.change(spec.getGeneration(), b -> {
                                b.getStatus().setLastPollAt(Instant.now());
                                if (!"DELIVERY_STORAGE_FAILED"
                                        .equals(b.getStatus().getLastErrorCode())) {
                                    b.getStatus().setLastErrorCode(null);
                                    b.getStatus()
                                            .setConnectionState(
                                                    b.getStatus().getContextReceivedAt() == null
                                                            ? WechatBinding.ConnectionState.AWAITING_MESSAGE
                                                            : WechatBinding.ConnectionState.ACTIVE);
                                }
                            }))
                            .then()
                            .onErrorResume(e -> {
                                if (!active.getAsBoolean()) return Mono.empty();
                                // A normal empty long-poll is not a connection failure and never advances the cursor.
                                if (e instanceof ChannelException ce && ce.code() == ChannelException.Code.TIMEOUT)
                                    return Mono.empty();
                                Mono<Void> report = bindings.change(spec.getGeneration(), b -> {
                                            b.getStatus().setConnectionState(WechatBinding.ConnectionState.DEGRADED);
                                            b.getStatus().setLastErrorCode(errorCode(e));
                                        })
                                        .then()
                                        .onErrorResume(ignored -> Mono.empty());
                                // Keep the encrypted batch and resume through the command's durable claims.
                                // A transient query/storage failure must not freeze all future inbound messages.
                                if (!(e instanceof ChannelException))
                                    log.warn(
                                            "ClawBot inbound processing will retry after failure: {}",
                                            e.getClass().getSimpleName());
                                return report.then(Mono.delay(Duration.ofSeconds(5)))
                                        .then();
                            });
                })
                .onErrorResume(e -> Mono.delay(Duration.ofSeconds(5)).then());
    }

    private Mono<Void> drain(WechatBinding binding, Map<String, String> values) {
        if (!values.containsKey("pendingBatch")) return Mono.empty();
        return Mono.defer(() -> {
            JsonNode batch;
            try {
                batch = json.readTree(values.get("pendingBatch"));
            } catch (Exception e) {
                return Mono.error(new IllegalStateException("INBOUND_STORAGE_INVALID"));
            }
            String context = values.get("context");
            long timestamp = Long.parseLong(values.getOrDefault("contextTimestamp", "0"));
            int receivedCount = 0;
            int acceptedCount = 0;
            for (var message : batch.path("msgs")) {
                ++receivedCount;
                if (!isOwnerMessage(binding, message)) continue;
                ++acceptedCount;
                long received = message.path("create_time_ms").asLong(0);
                if (received > timestamp
                        && message.path("context_token").isTextual()
                        && !message.path("context_token").asText().isBlank()) {
                    context = message.path("context_token").asText();
                    timestamp = received;
                }
            }
            if (receivedCount > 0) {
                log.info(
                        "ClawBot inbound batch: messages={}, ownerTexts={}, contextAvailable={}",
                        receivedCount,
                        acceptedCount,
                        context != null);
            }
            String newestContext = context;
            long newestTimestamp = timestamp;
            return Flux.fromIterable(batch.path("msgs"))
                    .concatMap(message -> commands.capture(binding, message))
                    .then(credentials.update(binding.getSpec().getCredentialRef(), data -> {
                        if (newestContext != null) {
                            data.put("context", newestContext);
                            data.put("contextTimestamp", Long.toString(newestTimestamp));
                        }
                        data.remove("pendingBatch");
                    }))
                    .then(
                            newestContext == null
                                    ? Mono.empty()
                                    : bindings.change(binding.getSpec().getGeneration(), b -> {
                                                b.getStatus()
                                                        .setContextReceivedAt(Instant.ofEpochMilli(newestTimestamp));
                                                if (!"DELIVERY_STORAGE_FAILED"
                                                        .equals(b.getStatus().getLastErrorCode())) {
                                                    b.getStatus()
                                                            .setConnectionState(WechatBinding.ConnectionState.ACTIVE);
                                                }
                                            })
                                            .then());
        });
    }

    public static boolean isOwnerText(WechatBinding binding, JsonNode message) {
        if (!isOwnerMessage(binding, message)) return false;
        for (var item : message.path("item_list")) if (item.path("type").asInt() != 1) return false;
        return true;
    }

    public static boolean isOwnerMessage(WechatBinding binding, JsonNode message) {
        var recipient = message.path("to_user_id");
        // The upstream protocol makes the recipient optional. The authenticated
        // bot channel and exact owner still identify a private inbound message.
        if (!recipient.isMissingNode()
                && (!recipient.isTextual()
                        || (!recipient.asText().isEmpty()
                                && !binding.getSpec().getBotId().equals(recipient.asText())))) return false;
        if (!message.path("message_id").isIntegralNumber()
                && !message.path("message_id").isTextual()) return false;
        if (message.path("message_id").asText().isBlank()
                || message.path("group_id").asText("").length() > 0
                || !binding.getSpec()
                        .getOwnerWechatUserId()
                        .equals(message.path("from_user_id").asText())
                || message.path("message_type").asInt() != 1
                || message.path("message_state").asInt() != 2
                || !message.path("item_list").isArray()
                || message.path("item_list").isEmpty()) return false;
        for (var item : message.path("item_list")) {
            int type = item.path("type").asInt();
            if (type == 1 && item.path("text_item").path("text").isTextual()) continue;
            if (type == 2 && item.path("image_item").path("media").isObject()) continue;
            return false;
        }
        return message.path("create_time_ms").canConvertToLong()
                && message.path("create_time_ms").asLong() > 0;
    }

    private static String errorCode(Throwable e) {
        return e instanceof ChannelException ce ? "WECHAT_" + ce.code().name() : "STORAGE_OR_CONNECTION_FAILED";
    }
}
