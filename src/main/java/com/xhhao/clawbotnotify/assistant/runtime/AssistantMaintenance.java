package com.xhhao.clawbotnotify.assistant.runtime;

import static run.halo.app.extension.index.query.Queries.*;

import com.xhhao.clawbotnotify.assistant.AssistantConversations;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/** Erases old encrypted bodies, retaining command/receipt tombstones so upstream replays stay harmless. */
@Component
public class AssistantMaintenance {
    private final ReactiveExtensionClient resources;
    private final CredentialStore secrets;
    private final AssistantSettings settings;
    private final AssistantConversations conversations;
    private final com.xhhao.clawbotnotify.media.WechatMediaInbox media;
    private final AtomicBoolean running = new AtomicBoolean();
    private Disposable subscription;
    private int scanPage = 1;
    private int conversationPage = 1;
    private int receiptPage = 1;
    private volatile Instant lastRunAt;
    private volatile int lastCleaned;
    private volatile String errorCode;

    public AssistantMaintenance(
            ReactiveExtensionClient resources,
            CredentialStore secrets,
            AssistantSettings settings,
            AssistantConversations conversations,
            com.xhhao.clawbotnotify.media.WechatMediaInbox media) {
        this.resources = resources;
        this.secrets = secrets;
        this.settings = settings;
        this.conversations = conversations;
        this.media = media;
    }

    public synchronized void start() {
        stop();
        subscription = Flux.interval(Duration.ZERO, Duration.ofMinutes(10))
                .concatMap(ignored -> tick().onErrorResume(error -> Mono.empty()))
                .subscribe();
    }

    public synchronized void stop() {
        if (subscription != null) subscription.dispose();
    }

    public Instant lastRunAt() {
        return lastRunAt;
    }

    public int lastCleaned() {
        return lastCleaned;
    }

    public String errorCode() {
        return errorCode;
    }

    public Mono<Integer> tick() {
        return Mono.defer(() -> {
            if (!running.compareAndSet(false, true)) return Mono.just(0);
            return media.cleanup()
                    .then(settings.get())
                    .flatMap(config -> {
                        Instant cutoff = Instant.now().minusSeconds(config.getRetentionDays() * 86400L);
                        var query = isNull("spec.cleanedAt")
                                .and(lessThan("spec.receivedAt", cutoff, true))
                                .and(equal("spec.state", "QUEUED"))
                                .and(isNull("spec.writeState")
                                        .or(in("spec.writeState", "SUCCEEDED", "REJECTED"))
                                        .or(equal("spec.type", "AI")
                                                .and(equal("spec.writeState", "AWAITING_APPROVAL"))));
                        return resources
                                .listBy(
                                        InboundCommand.class,
                                        ListOptions.builder().fieldQuery(query).build(),
                                        PageRequestImpl.of(scanPage, 100, Sort.by("spec.receivedAt", "metadata.name")))
                                .flatMapMany(page -> {
                                    scanPage = page.hasNext() ? scanPage + 1 : 1;
                                    return Flux.fromIterable(page.getItems());
                                })
                                .concatMap(command -> clean(command).map(done -> done ? 1 : 0))
                                .reduce(0, Integer::sum)
                                .flatMap(count -> cleanReceipts(cutoff)
                                        .then(cleanConversations(config))
                                        .thenReturn(count));
                    })
                    .doOnNext(count -> {
                        lastRunAt = Instant.now();
                        lastCleaned = count;
                        errorCode = null;
                    })
                    .doOnError(error -> {
                        lastRunAt = Instant.now();
                        errorCode = "AI_CLEANUP_FAILED";
                    })
                    .doFinally(signal -> running.set(false));
        });
    }

    private Mono<Void> cleanReceipts(Instant cutoff) {
        var query = isNull("spec.cleanedAt")
                .and(lessThan("spec.finishedAt", cutoff, true)
                        .or(isNull("spec.finishedAt").and(lessThan("metadata.creationTimestamp", cutoff, true))))
                .and(in("spec.state", "ACCEPTED", "DEAD", "CANCELLED"));
        return resources
                .listBy(
                        OutboundTask.class,
                        ListOptions.builder().fieldQuery(query).build(),
                        PageRequestImpl.of(receiptPage, 100, Sort.by("spec.finishedAt", "metadata.name")))
                .flatMapMany(page -> {
                    receiptPage = page.hasNext() ? receiptPage + 1 : 1;
                    return Flux.fromIterable(page.getItems());
                })
                .concatMap(task -> {
                    task.getSpec().setCleanedAt(Instant.now());
                    return resources.update(task);
                })
                .then();
    }

    private Mono<Void> cleanConversations(AssistantSettings.Config config) {
        return resources
                .listBy(
                        Secret.class,
                        ListOptions.builder()
                                .fieldQuery(startsWith("metadata.name", "clawbot-chat-"))
                                .build(),
                        PageRequestImpl.of(conversationPage, 100, Sort.by("metadata.name")))
                .flatMapMany(page -> {
                    conversationPage = page.hasNext() ? conversationPage + 1 : 1;
                    return Flux.fromIterable(page.getItems());
                })
                .filter(secret -> secret.getMetadata().getName().matches("clawbot-chat-[a-f0-9]{64}"))
                .concatMap(
                        secret -> conversations.trimStored(secret.getMetadata().getName(), config))
                .then();
    }

    private Mono<Boolean> previewExpired(InboundCommand command) {
        if (command.getSpec().getWriteState() != InboundCommand.WriteState.AWAITING_APPROVAL) return Mono.just(true);
        return resources
                .fetch(Secret.class, command.getSpec().getBodyRef())
                .flatMap(secret -> secrets.read(command.getSpec().getBodyRef())
                        .map(values -> values.containsKey("proposalExpiresAt")
                                && Instant.parse(values.get("proposalExpiresAt"))
                                        .isBefore(Instant.now())))
                .defaultIfEmpty(true);
    }

    private Mono<Boolean> clean(InboundCommand command) {
        var spec = command.getSpec();
        String receipt = "out-"
                + StableIds.hash(
                        "primary",
                        Long.toString(spec.getGeneration()),
                        "COMMAND",
                        command.getMetadata().getName());
        return previewExpired(command)
                .filter(Boolean::booleanValue)
                .flatMap(expired -> resources
                        .fetch(OutboundTask.class, receipt)
                        .map(task -> task.getSpec().getState())
                        .defaultIfEmpty(OutboundTask.State.ACCEPTED)
                        .filter(state -> state == OutboundTask.State.ACCEPTED
                                || state == OutboundTask.State.CANCELLED
                                || state == OutboundTask.State.DEAD)
                        .flatMap(state -> {
                            Mono<Boolean> shared = spec.getBodyRef() == null
                                    ? Mono.just(false)
                                    : resources
                                            .listBy(
                                                    InboundCommand.class,
                                                    ListOptions.builder()
                                                            .fieldQuery(equal("spec.bodyRef", spec.getBodyRef())
                                                                    .and(not(equal(
                                                                            "metadata.name",
                                                                            command.getMetadata()
                                                                                    .getName()))))
                                                            .build(),
                                                    PageRequestImpl.ofSize(1))
                                            .map(rows -> rows.getTotal() > 0);
                            return shared.flatMap(inUse -> {
                                if (inUse) return Mono.just(false);
                                Mono<Void> erase =
                                        spec.getBodyRef() == null ? Mono.empty() : secrets.delete(spec.getBodyRef());
                                return erase.then(Mono.defer(() -> {
                                    spec.setCleanedAt(Instant.now());
                                    return resources.update(command).thenReturn(true);
                                }));
                            });
                        })
                        .defaultIfEmpty(false))
                .defaultIfEmpty(false);
    }
}
