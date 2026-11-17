package com.xhhao.clawbotnotify.comment;

import static run.halo.app.extension.index.query.Queries.greaterThan;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.util.function.BooleanSupplier;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** The controller is a prompt hint; rotating bounded pages repair missed hints after downtime. */
@Component
public class CommentDiscovery {
    private final BindingRepository bindings;
    private final ReactiveExtensionClient resources;
    private final CommentLinkRepository links;
    private final OutboundQueue queue;
    private int commentPage = 1;
    private int linkPage = 1;
    private long generation;

    public CommentDiscovery(
            BindingRepository bindings,
            ReactiveExtensionClient resources,
            CommentLinkRepository links,
            OutboundQueue queue) {
        this.bindings = bindings;
        this.resources = resources;
        this.links = links;
        this.queue = queue;
    }

    public Mono<Void> observe(String name) {
        return bindings.current().filter(b -> b.getSpec().isEnabled()).flatMap(binding -> observe(binding, name));
    }

    private Mono<Void> observe(WechatBinding binding, String name) {
        long current = binding.getSpec().getGeneration();
        return resources
                .fetch(Comment.class, name)
                .flatMap(comment -> {
                    if (comment.getMetadata().getDeletionTimestamp() != null)
                        return links.forComment(current, name).flatMap(links::deleted);
                    if (!eligible(binding, comment)) return Mono.empty();
                    return links.ensure(current, comment)
                            .flatMap(link -> queue.enqueue(binding, "COMMENT", name, spec -> {
                                spec.setCommentName(name);
                                spec.setShortCode(link.getSpec().getShortCode());
                            }))
                            .then();
                })
                .then(resources.fetch(Comment.class, name).hasElement())
                .flatMap(exists ->
                        exists ? Mono.empty() : links.forComment(current, name).flatMap(links::deleted));
    }

    public static boolean eligible(WechatBinding binding, Comment comment) {
        var start = binding.getSpec().getNotificationStartAt();
        var created = comment.getMetadata().getCreationTimestamp();
        return binding.getSpec().isEnabled()
                && start != null
                && created != null
                && !created.isBefore(start)
                && supported(comment);
    }

    public static boolean supported(Comment comment) {
        var ref = comment.getSpec() == null ? null : comment.getSpec().getSubjectRef();
        return comment.getMetadata().getDeletionTimestamp() == null
                && ref != null
                && "content.halo.run".equals(ref.getGroup())
                && ("Post".equals(ref.getKind()) || "SinglePage".equals(ref.getKind()));
    }

    public Mono<Void> tick(BooleanSupplier active) {
        return bindings.current()
                .filter(b -> active.getAsBoolean()
                        && b.getSpec().isEnabled()
                        && b.getSpec().getNotificationStartAt() != null)
                .flatMap(binding -> {
                    if (generation != binding.getSpec().getGeneration()) {
                        generation = binding.getSpec().getGeneration();
                        commentPage = linkPage = 1;
                    }
                    return resources
                            .listBy(
                                    Comment.class,
                                    ListOptions.builder()
                                            .fieldQuery(greaterThan(
                                                    "metadata.creationTimestamp",
                                                    binding.getSpec().getNotificationStartAt(),
                                                    true))
                                            .build(),
                                    PageRequestImpl.of(
                                            commentPage, 100, Sort.by("metadata.creationTimestamp", "metadata.name")))
                            .flatMap(page -> Flux.fromIterable(page.getItems())
                                    .concatMap(c -> active.getAsBoolean()
                                            ? observe(binding, c.getMetadata().getName())
                                            : Mono.empty())
                                    .then(Mono.fromRunnable(() -> commentPage = page.hasNext() ? commentPage + 1 : 1)))
                            .then(links.page(generation, linkPage, 100, false))
                            .flatMap(page -> Flux.fromIterable(page.getItems())
                                    .filter(link -> link.getSpec().getTargetReplyName() == null)
                                    .concatMap(link -> active.getAsBoolean()
                                            ? observe(binding, link.getSpec().getCommentName())
                                            : Mono.empty())
                                    .then(Mono.fromRunnable(() -> linkPage = page.hasNext() ? linkPage + 1 : 1)));
                });
    }
}
