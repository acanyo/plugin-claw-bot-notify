package com.xhhao.clawbotnotify.binding;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.api.BindingView;
import com.xhhao.clawbotnotify.channel.ChannelException;
import com.xhhao.clawbotnotify.channel.WechatDestinationPolicy;
import com.xhhao.clawbotnotify.channel.WechatProtocolClient;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;

@Component
public class BindingService {
    private final BindingRepository bindings;
    private final BindingRuntime runtime;
    private final WechatProtocolClient channel;
    private final WechatDestinationPolicy destinations;
    private final CredentialStore credentials;
    private final CredentialCipher cipher;
    private final HaloIdentityVerifier halo;
    private final AtomicBoolean transition = new AtomicBoolean();
    private volatile LoginSession session;

    public BindingService(
            BindingRepository bindings,
            BindingRuntime runtime,
            WechatProtocolClient channel,
            WechatDestinationPolicy destinations,
            CredentialStore credentials,
            CredentialCipher cipher,
            HaloIdentityVerifier halo) {
        this.bindings = bindings;
        this.runtime = runtime;
        this.channel = channel;
        this.destinations = destinations;
        this.credentials = credentials;
        this.cipher = cipher;
        this.halo = halo;
    }

    public Mono<Void> start() {
        return runtime.recover()
                .then(bindings.current())
                .flatMap(binding -> {
                    if (!binding.getSpec().isEnabled()) return Mono.empty();
                    return credentials
                            .read(binding.getSpec().getCredentialRef())
                            .doOnNext(ignored -> runtime.start())
                            .then();
                })
                .onErrorResume(error -> bindings.current()
                        .flatMap(binding -> bindings.change(binding.getSpec().getGeneration(), b -> {
                            b.getStatus().setConnectionState(WechatBinding.ConnectionState.DEGRADED);
                            b.getStatus().setLastErrorCode("CREDENTIAL_RESTORE_FAILED");
                        }))
                        .then())
                .then();
    }

    public Mono<Void> stop() {
        var active = session;
        session = null;
        if (active != null) active.stop();
        return runtime.stop();
    }

    public Mono<BindingView> get() {
        return bindings.current()
                .map(this::view)
                .defaultIfEmpty(new BindingView(
                        "primary",
                        0,
                        null,
                        null,
                        null,
                        "UNBOUND",
                        false,
                        cipher.available(),
                        false,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        WechatProtocolClient.PROTOCOL_VERSION));
    }

    public Mono<BindingView> create(String username, String key) {
        var hash = requestHash(username, key);
        return exclusive(() -> {
            if (!cipher.available()) return Mono.error(conflict("CREDENTIAL_KEY_REQUIRED"));
            return bindings.current().defaultIfEmpty(new WechatBinding()).flatMap(old -> {
                if (old.getSpec() != null && hash.equals(old.getSpec().getBindingRequestHash()))
                    return Mono.just(view(old));
                var previous = session;
                session = null;
                if (previous != null) previous.stop();
                return runtime.stop().then(disable(old)).then(channel.login()).flatMap(qr -> {
                    long generation = old.getSpec() == null ? 1 : old.getSpec().getGeneration() + 1;
                    String oldRef = old.getSpec() == null ? null : old.getSpec().getCredentialRef();
                    if (old.getMetadata() == null) {
                        var metadata = new Metadata();
                        metadata.setName(BindingRepository.NAME);
                        old.setMetadata(metadata);
                    }
                    var spec = new WechatBinding.Spec();
                    spec.setHaloUsername(username);
                    spec.setGeneration(generation);
                    spec.setBindingRequestHash(hash);
                    spec.setApiBaseUrl(WechatDestinationPolicy.DEFAULT_BASE);
                    spec.setCredentialRef("clawbot-credentials-" + UUID.randomUUID());
                    old.setSpec(spec);
                    old.setStatus(new WechatBinding.Status());
                    return (oldRef == null ? Mono.<Void>empty() : credentials.delete(oldRef))
                            .then(bindings.save(old))
                            .map(saved -> {
                                var login = new LoginSession(
                                        generation,
                                        qr.path("qrcode").asText(),
                                        qr.path("qrcode_img_content").asText());
                                session = login;
                                beginPolling(login);
                                return view(saved);
                            });
                });
            });
        });
    }

    public Mono<BindingView> verifyCode(String username, String code) {
        return exclusive(() -> owned(username).map(binding -> {
            var active = session;
            if (active == null
                    || !"NEEDS_CODE".equals(active.state)
                    || Instant.now().isAfter(active.expiresAt)) throw conflict("QR_SESSION_REQUIRED");
            if (code == null || !code.matches("[0-9]{1,12}")) throw badRequest("INVALID_VERIFY_CODE");
            active.pendingPin = code;
            active.state = "VERIFYING";
            return view(binding);
        }));
    }

    public Mono<BindingView> replaceHaloCredential(String username, String token) {
        if (token == null || token.isBlank() || token.length() > 8192 || token.contains("\r") || token.contains("\n")) {
            return Mono.error(badRequest("INVALID_CREDENTIAL"));
        }
        return exclusive(() -> owned(username).flatMap(binding -> {
            if (binding.getSpec().getBotId() == null) return Mono.error(conflict("BINDING_CONFIRMATION_REQUIRED"));
            return halo.verify(token, binding.getSpec().getHaloUsername())
                    .then(credentials.update(
                            binding.getSpec().getCredentialRef(), values -> values.put("haloPat", token)))
                    .then(bindings.change(
                            binding.getSpec().getGeneration(), b -> b.getSpec().setHaloCredentialConfigured(true)))
                    .map(this::view);
        }));
    }

    public Mono<BindingView> activate(String username) {
        return exclusive(() -> owned(username).flatMap(binding -> {
            if (binding.getSpec().getBotId() == null || !binding.getSpec().isHaloCredentialConfigured()) {
                return Mono.error(conflict("HALO_CREDENTIAL_REQUIRED"));
            }
            return runtime.stop()
                    .then(credentials.read(binding.getSpec().getCredentialRef()))
                    .flatMap(values ->
                            halo.verify(values.get("haloPat"), binding.getSpec().getHaloUsername()))
                    .then(bindings.change(binding.getSpec().getGeneration(), b -> {
                        b.getSpec().setEnabled(true);
                        if (b.getSpec().getNotificationStartAt() == null)
                            b.getSpec().setNotificationStartAt(Instant.now());
                        b.getStatus().setConnectionState(WechatBinding.ConnectionState.AWAITING_MESSAGE);
                        b.getStatus().setLastErrorCode(null);
                    }))
                    .doOnNext(ignored -> runtime.start())
                    .map(this::view);
        }));
    }

    public Mono<BindingView> disconnect(String username) {
        return exclusive(() -> owned(username)
                .flatMap(binding -> stop().then(disable(binding))
                        .then(credentials.delete(binding.getSpec().getCredentialRef()))
                        .then(get())));
    }

    public Mono<WechatBinding> owned(String username) {
        return bindings.require().map(binding -> {
            if (!username.equals(binding.getSpec().getHaloUsername()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "BINDING_OWNER_REQUIRED");
            return binding;
        });
    }

    private Mono<Void> disable(WechatBinding binding) {
        if (binding.getSpec() == null) return Mono.empty();
        return bindings.change(binding.getSpec().getGeneration(), current -> {
                    current.getSpec().setEnabled(false);
                    current.getSpec().setHaloCredentialConfigured(false);
                    current.getStatus().setConnectionState(WechatBinding.ConnectionState.DISABLED);
                })
                .doOnNext(saved -> {
                    binding.setMetadata(saved.getMetadata());
                    binding.setSpec(saved.getSpec());
                    binding.setStatus(saved.getStatus());
                })
                .then();
    }

    private void beginPolling(LoginSession active) {
        active.polling = Mono.defer(() -> {
                    if (session != active || Instant.now().isAfter(active.expiresAt)) {
                        active.state = "EXPIRED";
                        active.pendingPin = null;
                        return Mono.just(false);
                    }
                    if ("NEEDS_CODE".equals(active.state) && active.pendingPin == null) return Mono.just(true);
                    return channel.loginStatus(active.base, active.qr, active.pendingPin)
                            .flatMap(body -> handleLoginStatus(active, body))
                            .onErrorResume(error -> {
                                if (error instanceof ChannelException ce && ce.code() == ChannelException.Code.TIMEOUT)
                                    return Mono.just(true);
                                if (error instanceof ResponseStatusException) return Mono.just(true);
                                active.errorCode = error instanceof ChannelException ce
                                        ? "WECHAT_" + ce.code().name()
                                        : "BINDING_STORAGE_FAILED";
                                active.state = "FAILED";
                                active.pendingPin = null;
                                return Mono.just(false);
                            });
                })
                .delayElement(Duration.ofSeconds(1))
                .repeat()
                .takeUntil(keep -> !keep)
                .subscribe(ignored -> {}, ignored -> {});
    }

    private Mono<Boolean> handleLoginStatus(LoginSession active, JsonNode body) {
        String state = body.path("status").asText();
        switch (state) {
            case "wait" -> active.state = "WAITING_SCAN";
            case "scaned" -> {
                active.state = "SCANNED";
                active.pendingPin = null;
            }
            case "need_verifycode", "confirmed_but_need_verifycode" -> {
                active.state = "NEEDS_CODE";
                active.pendingPin = null;
            }
            case "expired" -> {
                active.state = "EXPIRED";
                active.pendingPin = null;
                return Mono.just(false);
            }
            case "verify_code_blocked" -> {
                active.state = "VERIFY_BLOCKED";
                active.pendingPin = null;
                return Mono.just(false);
            }
            case "binded_redirect" -> {
                active.state = "ALREADY_BOUND";
                return Mono.just(false);
            }
            case "scaned_but_redirect" -> {
                active.base = destinations
                        .validate("https://" + WechatProtocolClient.requiredText(body, "redirect_host"))
                        .toString();
                active.state = "SCANNED";
            }
            case "confirmed" -> {
                String token = WechatProtocolClient.requiredText(body, "bot_token");
                String bot = WechatProtocolClient.requiredText(body, "ilink_bot_id");
                String owner = WechatProtocolClient.requiredText(body, "ilink_user_id");
                String base = destinations
                        .validate(
                                body.hasNonNull("baseurl")
                                        ? body.path("baseurl").asText()
                                        : active.base)
                        .toString();
                return exclusive(() -> bindings.require().flatMap(binding -> {
                    if (session != active || binding.getSpec().getGeneration() != active.generation)
                        return Mono.just(false);
                    return credentials
                            .create(binding.getSpec().getCredentialRef(), Map.of("botToken", token))
                            .then(bindings.change(active.generation, b -> {
                                b.getSpec().setBotId(bot);
                                b.getSpec().setOwnerWechatUserId(owner);
                                b.getSpec().setApiBaseUrl(base);
                                b.getStatus().setConnectionState(WechatBinding.ConnectionState.AWAITING_ACTIVATION);
                            }))
                            .map(saved -> {
                                active.state = "CONFIRMED";
                                active.pendingPin = null;
                                return false;
                            });
                }));
            }
            default -> {
                active.state = "UNSUPPORTED";
                return Mono.just(false);
            }
        }
        return Mono.just(true);
    }

    private BindingView view(WechatBinding binding) {
        var spec = binding.getSpec();
        var status = binding.getStatus();
        var active = session;
        boolean qr = active != null && active.generation == spec.getGeneration();
        return new BindingView(
                "primary",
                spec.getGeneration(),
                spec.getHaloUsername(),
                mask(spec.getBotId()),
                mask(spec.getOwnerWechatUserId()),
                status.getConnectionState().name(),
                spec.isEnabled(),
                cipher.available(),
                spec.isHaloCredentialConfigured(),
                qr
                        ? active.state
                        : status.getConnectionState() == WechatBinding.ConnectionState.BINDING ? "EXPIRED" : null,
                qr && ("WAITING_SCAN".equals(active.state) || "SCANNED".equals(active.state)) ? active.content : null,
                qr ? active.expiresAt : null,
                status.getLastPollAt(),
                status.getLastAcceptedAt(),
                status.getContextReceivedAt(),
                qr && active.errorCode != null ? active.errorCode : status.getLastErrorCode(),
                WechatProtocolClient.PROTOCOL_VERSION);
    }

    private <T> Mono<T> exclusive(Supplier<Mono<T>> work) {
        return Mono.defer(() -> {
            if (!transition.compareAndSet(false, true)) return Mono.error(conflict("BINDING_TRANSITION_IN_PROGRESS"));
            return Mono.defer(work).doFinally(ignored -> transition.set(false));
        });
    }

    public static String requestHash(String scope, String key) {
        if (key == null || !key.matches("[a-zA-Z0-9._-]{1,100}")) throw badRequest("IDEMPOTENCY_KEY_REQUIRED");
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((scope + ":" + key).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HASH_UNAVAILABLE");
        }
    }

    private static String mask(String text) {
        return text == null ? null : "••••" + text.substring(Math.max(0, text.length() - 4));
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
