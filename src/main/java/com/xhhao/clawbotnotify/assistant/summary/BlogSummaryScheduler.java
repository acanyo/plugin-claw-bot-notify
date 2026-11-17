package com.xhhao.clawbotnotify.assistant.summary;

import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/** Durable per-slot command claims prevent repeated model requests across ticks and restarts. */
@Component
public class BlogSummaryScheduler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BlogSummaryScheduler.class);
    private final AssistantSettings settings;
    private final BindingRepository bindings;
    private final CredentialStore secrets;
    private final ReactiveExtensionClient resources;
    private final ScheduledBlogSummary summaries;
    private final OutboundQueue queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Instant startedAt = Instant.now();
    private Disposable subscription;
    private volatile String errorCode;

    public BlogSummaryScheduler(
            AssistantSettings settings,
            BindingRepository bindings,
            CredentialStore secrets,
            ReactiveExtensionClient resources,
            ScheduledBlogSummary summaries,
            OutboundQueue queue) {
        this.settings = settings;
        this.bindings = bindings;
        this.secrets = secrets;
        this.resources = resources;
        this.summaries = summaries;
        this.queue = queue;
    }

    public synchronized void start() {
        stop();
        subscription = Flux.interval(Duration.ZERO, Duration.ofMinutes(1))
                .concatMap(ignored -> tick(Instant.now()).onErrorResume(error -> Mono.empty()))
                .subscribe();
    }

    public synchronized void stop() {
        if (subscription != null) subscription.dispose();
    }

    public String errorCode() {
        return errorCode;
    }

    public Mono<Void> tick(Instant now) {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) return Mono.empty();
            return settings.get()
                    .filter(config ->
                            config.isEnabled() && config.isAllowWrites() && config.isBlogSummaryScheduleEnabled())
                    .flatMap(config -> bindings.current()
                            .filter(binding -> binding.getSpec().isEnabled())
                            .flatMap(binding -> check(binding, config, now)))
                    .doOnSuccess(ignored -> errorCode = null)
                    .doOnError(error -> {
                        String code = AssistantTools.safeCode(error);
                        if (!code.equals(errorCode)) log.warn("Scheduled blog summary paused: {}", code);
                        errorCode = code;
                    })
                    .doFinally(signal -> running.set(false));
        });
    }

    private Mono<Void> check(WechatBinding binding, AssistantSettings.Config config, Instant now) {
        String identity = BlogSummarySchedule.identity(config);
        String scheduleRef = "clawbot-summary-schedule-"
                + StableIds.hash(
                        Long.toString(binding.getSpec().getGeneration()),
                        binding.getSpec().getBotId(),
                        binding.getSpec().getOwnerWechatUserId(),
                        identity);
        return resources
                .fetch(Secret.class, scheduleRef)
                .flatMap(secret -> secrets.read(scheduleRef))
                .switchIfEmpty(secrets.create(scheduleRef, Map.of("activatedAt", now.toString()))
                        .then(secrets.read(scheduleRef))
                        .onErrorResume(error -> secrets.read(scheduleRef)))
                .flatMap(state -> {
                    var window = BlogSummarySchedule.latest(config, now);
                    Mono<Void> recovery = state.containsKey("command")
                            ? resources
                                    .fetch(InboundCommand.class, state.get("command"))
                                    .flatMap(command -> resume(binding, command))
                                    .then()
                            : Mono.empty();
                    if (!window.end().isAfter(Instant.parse(state.get("activatedAt")))) return recovery;
                    String id = StableIds.hash(scheduleRef, window.end().toString());
                    String name = "in-" + id;
                    String bodyRef = "clawbot-body-" + id;
                    return recovery.then(resources
                            .fetch(InboundCommand.class, name)
                            .map(command -> new Claim(command, false))
                            .switchIfEmpty(Mono.defer(() -> secrets.create(
                                            bodyRef,
                                            Map.of(
                                                    "body",
                                                    "定时博客总结：" + window.period(),
                                                    "scheduledSummaryAt",
                                                    window.end().toString(),
                                                    "scheduledScheduleKey",
                                                    identity))
                                    .onErrorResume(
                                            error -> secrets.read(bodyRef).then())
                                    .then(Mono.defer(() -> {
                                        var command = new InboundCommand();
                                        var metadata = new Metadata();
                                        metadata.setName(name);
                                        command.setMetadata(metadata);
                                        var spec = new InboundCommand.Spec();
                                        spec.setGeneration(binding.getSpec().getGeneration());
                                        spec.setType(InboundCommand.Type.AI);
                                        spec.setReceivedAt(now);
                                        spec.setStartedAt(Instant.now());
                                        spec.setBodyRef(bodyRef);
                                        spec.setWriteState(InboundCommand.WriteState.EXECUTING);
                                        command.setSpec(spec);
                                        return resources
                                                .create(command)
                                                .map(created -> new Claim(created, true))
                                                .onErrorResume(error -> resources
                                                        .fetch(InboundCommand.class, name)
                                                        .map(existing -> new Claim(existing, false))
                                                        .switchIfEmpty(Mono.error(error)));
                                    }))))
                            .flatMap(claim -> secrets.update(scheduleRef, values -> values.put("command", name))
                                    .then(
                                            claim.owned()
                                                    ? summaries
                                                            .process(binding, claim.command(), window)
                                                            .flatMap(done -> deliver(binding, done))
                                                    : resume(binding, claim.command()))));
                });
    }

    private Mono<Void> resume(WechatBinding binding, InboundCommand command) {
        if (command.getSpec().getState() == InboundCommand.State.QUEUED) return Mono.empty();
        if (command.getSpec().getWriteState() == InboundCommand.WriteState.EXECUTING) {
            if (command.getSpec().getStartedAt() == null
                    || !command.getSpec().getStartedAt().isBefore(startedAt)) return Mono.empty();
            return summaries.recover(command).flatMap(done -> deliver(binding, done));
        }
        return deliver(binding, command);
    }

    private Mono<Void> deliver(WechatBinding binding, InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef()).flatMap(values -> {
            Mono<Void> delivery = "true".equals(values.get("scheduledEmpty"))
                    ? Mono.empty()
                    : queue.enqueue(
                                    binding,
                                    "COMMAND",
                                    command.getMetadata().getName(),
                                    spec -> spec.setCommandName(
                                            command.getMetadata().getName()))
                            .then();
            return delivery.then(Mono.defer(() -> {
                command.getSpec().setState(InboundCommand.State.QUEUED);
                return resources.update(command).then();
            }));
        });
    }

    private record Claim(InboundCommand command, boolean owned) {}
}
