package com.xhhao.clawbotnotify.delivery;

import static run.halo.app.extension.index.query.Queries.*;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.channel.ChannelException;
import com.xhhao.clawbotnotify.channel.WechatProtocolClient;
import com.xhhao.clawbotnotify.command.QuotedCommentResolver;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** Dispatches durable tasks; ambiguous attempts are never replayed. */
@Component
public class OutboundDeliveryWorker {
    public static final String TEST_TEXT = "Halo ClawBot 连接测试：微信接口已接收后，请在手机确认消息。可发送“帮助”查看评论通知与只读指令。";
    private final BindingRepository bindings;
    private final ReactiveExtensionClient resources;
    private final CredentialStore credentials;
    private final WechatProtocolClient channel;
    private final MessageComposer composer;

    public OutboundDeliveryWorker(
            BindingRepository bindings,
            ReactiveExtensionClient resources,
            CredentialStore credentials,
            WechatProtocolClient channel,
            MessageComposer composer) {
        this.bindings = bindings;
        this.resources = resources;
        this.credentials = credentials;
        this.channel = channel;
        this.composer = composer;
    }

    public Mono<Void> recoverInterrupted() {
        // A request cancelled after dispatch may already have reached WeChat.
        return tasksIn(0, 0, OutboundTask.State.SENDING)
                .concatMap(task -> {
                    task.getSpec().setState(OutboundTask.State.UNKNOWN);
                    task.getSpec().setErrorCode("STOPPED_DURING_SEND");
                    task.getSpec().setFinishedAt(Instant.now());
                    return resources.update(task);
                })
                .then();
    }

    public Mono<Void> tick(BooleanSupplier active) {
        return bindings.current()
                .filter(binding -> active.getAsBoolean() && binding.getSpec().isEnabled())
                .flatMap(binding -> credentials
                        .read(binding.getSpec().getCredentialRef())
                        .flatMap(values -> tasksIn(
                                        contextVersion(values),
                                        binding.getSpec().getGeneration(),
                                        OutboundTask.State.PENDING,
                                        OutboundTask.State.WAITING_CONTEXT)
                                .concatMap(task ->
                                        currentProgress(task).flatMap(eligible -> dispatch(active, binding, eligible)))
                                .then()))
                .onErrorResume(e -> bindings.current()
                        .flatMap(binding -> bindings.change(binding.getSpec().getGeneration(), b -> {
                            b.getStatus().setConnectionState(WechatBinding.ConnectionState.DEGRADED);
                            b.getStatus().setLastErrorCode("DELIVERY_STORAGE_FAILED");
                        }))
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.never()));
    }

    private Mono<OutboundTask> currentProgress(OutboundTask task) {
        if (!"AI_PROGRESS".equals(task.getSpec().getKind())) return Mono.just(task);
        return resources
                .fetch(InboundCommand.class, task.getSpec().getCommandName())
                .filter(command ->
                        command.getSpec().getGeneration() == task.getSpec().getGeneration()
                                && command.getSpec().getWriteState() == InboundCommand.WriteState.EXECUTING)
                .map(command -> task)
                .switchIfEmpty(Mono.defer(() -> {
                    task.getSpec().setState(OutboundTask.State.CANCELLED);
                    task.getSpec().setFinishedAt(Instant.now());
                    return resources.update(task).then(Mono.empty());
                }));
    }

    private Mono<Void> dispatch(BooleanSupplier active, WechatBinding binding, OutboundTask task) {
        if (!active.getAsBoolean()) return Mono.empty();
        var spec = task.getSpec();
        if (spec.getNextAttemptAt() != null && spec.getNextAttemptAt().isAfter(Instant.now())) return Mono.empty();
        if (spec.getGeneration() != binding.getSpec().getGeneration()
                || !binding.getSpec().getOwnerWechatUserId().equals(spec.getTargetUserId())) {
            spec.setState(OutboundTask.State.CANCELLED);
            return resources.update(task).then();
        }
        return credentials.read(binding.getSpec().getCredentialRef()).flatMap(values -> {
            if (!active.getAsBoolean()) return Mono.empty();
            if (!values.containsKey("context")) {
                if (spec.getState() == OutboundTask.State.WAITING_CONTEXT) return Mono.empty();
                spec.setState(OutboundTask.State.WAITING_CONTEXT);
                return resources.update(task).then();
            }
            return composer.compose(binding, task, values)
                    .onErrorResume(error -> {
                        if ("COMMAND".equals(spec.getKind()))
                            return Mono.just("暂时无法读取评论，请在 Halo 连接页面检查 PAT 身份及“评论查看”权限，然后重新发送指令。");
                        spec.setErrorCode("HALO_COMMENT_READ_FAILED");
                        spec.setNextAttemptAt(Instant.now().plusSeconds(30));
                        return resources.update(task).then(Mono.empty());
                    })
                    .defaultIfEmpty("")
                    .flatMap(text -> {
                        if (text.isEmpty()) {
                            // A failed preflight remains pending; a deleted source is cancelled.
                            if (spec.getNextAttemptAt() == null
                                    || !spec.getNextAttemptAt().isAfter(Instant.now())) {
                                spec.setState(OutboundTask.State.CANCELLED);
                                spec.setErrorCode("SOURCE_UNAVAILABLE");
                                spec.setFinishedAt(Instant.now());
                                return resources.update(task).then();
                            }
                            return Mono.empty();
                        }
                        spec.setErrorCode(null);
                        spec.setNextAttemptAt(null);
                        spec.setQuoteTextHash(QuotedCommentResolver.fingerprint(text));
                        spec.setContextVersion(contextVersion(values));
                        spec.setState(OutboundTask.State.SENDING);
                        spec.setAttemptedAt(Instant.now());
                        return resources.update(task).flatMap(claimed -> {
                            if (!active.getAsBoolean()) return Mono.empty();
                            var claimedSpec = claimed.getSpec();
                            Mono<String> sending = spec.getQuotedMessageId() == null
                                    ? channel.send(
                                            binding.getSpec().getApiBaseUrl(),
                                            values.get("botToken"),
                                            spec.getTargetUserId(),
                                            values.get("context"),
                                            spec.getClientId(),
                                            text)
                                    : channel.sendQuoted(
                                            binding.getSpec().getApiBaseUrl(),
                                            values.get("botToken"),
                                            spec.getTargetUserId(),
                                            values.get("context"),
                                            spec.getClientId(),
                                            text,
                                            spec.getQuotedMessageId());
                            return sending.map(id -> new SendOutcome(OutboundTask.State.ACCEPTED, id, null))
                                    .onErrorResume(error -> {
                                        // Only classify the HTTP attempt. A later storage failure cannot undo an
                                        // accepted send.
                                        boolean rejected = error instanceof ChannelException ce
                                                && ce.code() == ChannelException.Code.BUSINESS_REJECTED;
                                        return Mono.just(new SendOutcome(
                                                rejected
                                                        ? OutboundTask.State.WAITING_CONTEXT
                                                        : OutboundTask.State.UNKNOWN,
                                                null,
                                                errorCode(error)));
                                    })
                                    .flatMap(outcome -> {
                                        claimedSpec.setState(outcome.state());
                                        claimedSpec.setUpstreamMessageId(outcome.messageId());
                                        claimedSpec.setErrorCode(outcome.error());
                                        claimedSpec.setFinishedAt(
                                                outcome.state() == OutboundTask.State.WAITING_CONTEXT
                                                        ? null
                                                        : Instant.now());
                                        return resources
                                                .update(claimed)
                                                .flatMap(saved -> outcome.state() == OutboundTask.State.ACCEPTED
                                                        ? bindings.change(
                                                                        spec.getGeneration(),
                                                                        b -> b.getStatus()
                                                                                .setLastAcceptedAt(Instant.now()))
                                                                .then()
                                                        : Mono.empty());
                                    });
                        });
                    });
        });
    }

    private Flux<OutboundTask> tasksIn(long contextVersion, long generation, OutboundTask.State... states) {
        var condition = equal("spec.state", states[0].name());
        for (int i = 1; i < states.length; i++)
            condition = condition.or(equal("spec.state", states[i].name())
                    .and(lessThan("spec.contextVersion", contextVersion)
                            .or(not(equal("spec.generation", generation)))));
        if (states[0] != OutboundTask.State.SENDING)
            condition =
                    condition.and(isNull("spec.nextAttemptAt").or(lessThan("spec.nextAttemptAt", Instant.now(), true)));
        return resources
                .listBy(
                        OutboundTask.class,
                        ListOptions.builder().fieldQuery(condition).build(),
                        PageRequestImpl.of(1, 100, Sort.by("metadata.creationTimestamp")))
                .flatMapMany(page -> Flux.fromIterable(page.getItems()));
    }

    private static long contextVersion(java.util.Map<String, String> values) {
        return Long.parseLong(values.getOrDefault("contextTimestamp", values.containsKey("context") ? "1" : "0"));
    }

    private static String errorCode(Throwable e) {
        return e instanceof ChannelException ce ? "WECHAT_" + ce.code().name() : "STORAGE_OR_CONNECTION_FAILED";
    }

    private record SendOutcome(OutboundTask.State state, String messageId, String error) {}
}
