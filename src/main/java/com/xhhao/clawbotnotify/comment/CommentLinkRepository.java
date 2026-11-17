package com.xhhao.clawbotnotify.comment;

import static run.halo.app.extension.index.query.Queries.equal;

import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.support.StableIds;
import java.security.SecureRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class CommentLinkRepository {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private final ReactiveExtensionClient resources;
    private final Supplier<String> codes;

    @Autowired
    public CommentLinkRepository(ReactiveExtensionClient resources) {
        this(resources, () -> {
            var random = new SecureRandom();
            var value = new StringBuilder("C-");
            for (int i = 0; i < 6; i++) value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            return value.toString();
        });
    }

    CommentLinkRepository(ReactiveExtensionClient resources, Supplier<String> codes) {
        this.resources = resources;
        this.codes = codes;
    }

    public Mono<CommentLink> forComment(long generation, String name) {
        return forTarget(generation, name, null);
    }

    public Mono<CommentLink> forTarget(long generation, String commentName, String replyName) {
        return resources.fetch(CommentLink.class, name(generation, commentName, replyName));
    }

    public Mono<CommentLink> ensureReply(long generation, Comment comment, Reply reply) {
        String replyName = reply.getMetadata().getName();
        return forTarget(generation, comment.getMetadata().getName(), replyName)
                .switchIfEmpty(Mono.defer(() -> allocate(generation, comment, replyName, 0)));
    }

    public Mono<CommentLink> forReply(long generation, String replyName) {
        return resources
                .listBy(
                        CommentLink.class,
                        ListOptions.builder()
                                .fieldQuery(equal("spec.generation", generation)
                                        .and(equal("spec.targetReplyName", replyName)))
                                .build(),
                        PageRequestImpl.ofSize(1))
                .flatMap(page -> Mono.justOrEmpty(page.getItems().stream().findFirst()));
    }

    public Mono<CommentLink> forCode(long generation, String code) {
        return resources
                .listBy(
                        CommentLink.class,
                        ListOptions.builder()
                                .fieldQuery(equal("spec.scopeCode", "primary:" + generation + ":" + code))
                                .build(),
                        PageRequestImpl.ofSize(1))
                .flatMap(page -> Mono.justOrEmpty(page.getItems().stream().findFirst()));
    }

    public Mono<CommentLink> ensure(long generation, Comment comment) {
        return forComment(generation, comment.getMetadata().getName())
                .switchIfEmpty(Mono.defer(() -> allocate(generation, comment, null, 0)));
    }

    private Mono<CommentLink> allocate(long generation, Comment comment, String replyName, int attempt) {
        if (attempt >= 10) return Mono.error(new IllegalStateException("SHORT_CODE_EXHAUSTED"));
        String code = codes.get();
        return forCode(generation, code).hasElement().flatMap(exists -> {
            if (exists) return allocate(generation, comment, replyName, attempt + 1);
            var link = new CommentLink();
            var metadata = new Metadata();
            metadata.setName(name(generation, comment.getMetadata().getName(), replyName));
            link.setMetadata(metadata);
            var spec = new CommentLink.Spec();
            spec.setGeneration(generation);
            spec.setShortCode(code);
            spec.setCommentName(comment.getMetadata().getName());
            spec.setTargetReplyName(replyName);
            spec.setSubjectRef(comment.getSpec().getSubjectRef());
            link.setSpec(spec);
            // The scopeCode unique index makes concurrent reservations atomic.
            return resources
                    .create(link)
                    .onErrorResume(e -> forTarget(generation, spec.getCommentName(), replyName)
                            .switchIfEmpty(Mono.defer(() -> forCode(generation, code)
                                    .hasElement()
                                    .flatMap(collision -> collision
                                            ? allocate(generation, comment, replyName, attempt + 1)
                                            : Mono.error(e)))));
        });
    }

    public Mono<Void> deleted(CommentLink link) {
        return Mono.defer(() -> resources
                        .fetch(CommentLink.class, link.getMetadata().getName())
                        .flatMap(current -> {
                            if (current.getSpec().getState() == CommentLink.State.DELETED) return Mono.empty();
                            current.getSpec().setState(CommentLink.State.DELETED);
                            return resources.update(current).then();
                        }))
                .retryWhen(Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance));
    }

    public Mono<Void> answered(long generation, String commentName) {
        return answered(generation, commentName, null);
    }

    public Mono<Void> answered(long generation, String commentName, String replyName) {
        return Mono.defer(() -> forTarget(generation, commentName, replyName).flatMap(link -> {
                    if (link.getSpec().getState() == CommentLink.State.DELETED
                            || link.getSpec().getState() == CommentLink.State.ANSWERED) return Mono.empty();
                    link.getSpec().setState(CommentLink.State.ANSWERED);
                    return resources.update(link).then();
                }))
                .retryWhen(Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance));
    }

    public Mono<Void> answeredFromBackend(long generation, String commentName) {
        return changeState(generation, commentName, CommentLink.State.ANSWERED, true);
    }

    public Mono<Void> changeState(long generation, String commentName, CommentLink.State state, boolean openOnly) {
        return changeState(generation, commentName, null, state, openOnly);
    }

    public Mono<Void> changeState(
            long generation, String commentName, String replyName, CommentLink.State state, boolean openOnly) {
        return Mono.defer(() -> forTarget(generation, commentName, replyName).flatMap(link -> {
                    var current = link.getSpec().getState();
                    if (current == CommentLink.State.DELETED && !openOnly)
                        return Mono.error(new IllegalStateException("COMMENT_DELETED"));
                    if (current == CommentLink.State.DELETED
                            || current == state
                            || openOnly && current != CommentLink.State.OPEN) return Mono.empty();
                    link.getSpec().setState(state);
                    return resources.update(link).then();
                }))
                .retryWhen(Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance));
    }

    public Mono<ListResult<CommentLink>> page(long generation, int page, int size, boolean openOnly) {
        var query = equal("spec.generation", generation);
        if (openOnly) query = query.and(equal("spec.state", CommentLink.State.OPEN.name()));
        return resources.listBy(
                CommentLink.class,
                ListOptions.builder().fieldQuery(query).build(),
                PageRequestImpl.of(page, size, Sort.by("metadata.creationTimestamp", "metadata.name")));
    }

    private static String name(long generation, String comment, String replyName) {
        return "link-"
                + (replyName == null
                        ? StableIds.hash("primary", Long.toString(generation), comment)
                        : StableIds.hash("primary", Long.toString(generation), "Reply", comment, replyName));
    }
}
