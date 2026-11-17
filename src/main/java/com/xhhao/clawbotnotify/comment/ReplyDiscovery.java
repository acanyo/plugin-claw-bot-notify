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
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** New visitor replies have their own durable target; old parent comments remain eligible. */
@Component
public class ReplyDiscovery {
    private final BindingRepository bindings;
    private final ReactiveExtensionClient resources;
    private final CommentLinkRepository links;
    private final OutboundQueue queue;
    private final CommentAuthors authors;
    private int page = 1, linkPage = 1;
    private long generation;

    public ReplyDiscovery(
            BindingRepository bindings,
            ReactiveExtensionClient resources,
            CommentLinkRepository links,
            OutboundQueue queue,
            CommentAuthors authors) {
        this.bindings = bindings;
        this.resources = resources;
        this.links = links;
        this.queue = queue;
        this.authors = authors;
    }

    public Mono<Void> observe(String name) {
        return bindings.current().filter(b -> b.getSpec().isEnabled()).flatMap(b -> observe(b, name));
    }

    private Mono<Void> observe(WechatBinding binding, String name) {
        long current = binding.getSpec().getGeneration();
        return resources
                .fetch(Reply.class, name)
                .filter(reply -> eligible(binding, reply))
                .flatMap(reply -> resources
                        .fetch(Comment.class, reply.getSpec().getCommentName())
                        .filter(CommentDiscovery::supported)
                        .flatMap(parent -> authors.moderator(reply.getSpec().getOwner())
                                .filter(moderator -> !moderator && !ownReply(binding, reply))
                                .flatMap(ignored -> links.ensureReply(current, parent, reply))
                                .flatMap(link -> queue.enqueue(binding, "REPLY", name, spec -> {
                                    spec.setCommentName(parent.getMetadata().getName());
                                    spec.setTargetReplyName(name);
                                    spec.setShortCode(link.getSpec().getShortCode());
                                }))))
                .then(resources
                        .fetch(Reply.class, name)
                        .filter(reply -> eligible(binding, reply))
                        .flatMap(reply -> resources
                                .fetch(Comment.class, reply.getSpec().getCommentName())
                                .filter(CommentDiscovery::supported))
                        .hasElement())
                .flatMap(exists ->
                        exists ? Mono.empty() : links.forReply(current, name).flatMap(links::deleted));
    }

    private static boolean ownReply(WechatBinding binding, Reply reply) {
        var owner = reply.getSpec().getOwner();
        return owner != null
                && User.KIND.equals(owner.getKind())
                && owner.getName() != null
                && owner.getName().equals(binding.getSpec().getHaloUsername());
    }

    public static boolean eligible(WechatBinding binding, Reply reply) {
        var start = binding.getSpec().getNotificationStartAt();
        var created = reply.getMetadata().getCreationTimestamp();
        return binding.getSpec().isEnabled()
                && start != null
                && created != null
                && !created.isBefore(start)
                && reply.getMetadata().getDeletionTimestamp() == null
                && reply.getSpec() != null
                && reply.getSpec().getCommentName() != null;
    }

    public Mono<Void> tick(BooleanSupplier active) {
        return bindings.current()
                .filter(b -> active.getAsBoolean()
                        && b.getSpec().isEnabled()
                        && b.getSpec().getNotificationStartAt() != null)
                .flatMap(binding -> {
                    if (generation != binding.getSpec().getGeneration()) {
                        generation = binding.getSpec().getGeneration();
                        page = linkPage = 1;
                    }
                    return resources
                            .listBy(
                                    Reply.class,
                                    ListOptions.builder()
                                            .fieldQuery(greaterThan(
                                                    "metadata.creationTimestamp",
                                                    binding.getSpec().getNotificationStartAt(),
                                                    true))
                                            .build(),
                                    PageRequestImpl.of(
                                            page, 100, Sort.by("metadata.creationTimestamp", "metadata.name")))
                            .flatMap(result -> Flux.fromIterable(result.getItems())
                                    .concatMap(reply -> active.getAsBoolean()
                                            ? observe(
                                                    binding, reply.getMetadata().getName())
                                            : Mono.empty())
                                    .then(Mono.fromRunnable(() -> page = result.hasNext() ? page + 1 : 1)))
                            .then(links.page(generation, linkPage, 100, false))
                            .flatMap(result -> Flux.fromIterable(result.getItems())
                                    .filter(link -> link.getSpec().getTargetReplyName() != null)
                                    .concatMap(link -> active.getAsBoolean()
                                            ? observe(binding, link.getSpec().getTargetReplyName())
                                            : Mono.empty())
                                    .then(Mono.fromRunnable(() -> linkPage = result.hasNext() ? linkPage + 1 : 1)));
                });
    }
}
