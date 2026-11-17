package com.xhhao.clawbotnotify.comment;

import static run.halo.app.extension.index.query.Queries.greaterThan;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.util.function.BooleanSupplier;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** Reply events are hints; a rotating scan repairs missed events. Content authorization always uses the owner's PAT. */
@Component
public class ReplyProcessingSynchronizer {
    private final BindingRepository bindings;
    private final ReactiveExtensionClient resources;
    private final CommentLinkRepository links;
    private final CredentialStore credentials;
    private final HaloCommentReader comments;
    private final CommentAuthors authors;
    private int page = 1;
    private long generation;

    public ReplyProcessingSynchronizer(
            BindingRepository bindings,
            ReactiveExtensionClient resources,
            CommentLinkRepository links,
            CredentialStore credentials,
            HaloCommentReader comments,
            CommentAuthors authors) {
        this.bindings = bindings;
        this.resources = resources;
        this.links = links;
        this.credentials = credentials;
        this.comments = comments;
        this.authors = authors;
    }

    public Mono<Void> observe(String name) {
        return bindings.current()
                .filter(b -> b.getSpec().isEnabled())
                .flatMap(binding ->
                        resources.fetch(Reply.class, name).flatMap(reply -> synchronize(binding, reply, () -> true)));
    }

    public Mono<Void> tick(BooleanSupplier active) {
        return bindings.current()
                .filter(b -> active.getAsBoolean()
                        && b.getSpec().isEnabled()
                        && b.getSpec().getNotificationStartAt() != null)
                .flatMap(binding -> {
                    if (generation != binding.getSpec().getGeneration()) {
                        generation = binding.getSpec().getGeneration();
                        page = 1;
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
                                    .concatMap(reply -> synchronize(binding, reply, active))
                                    .then(Mono.fromRunnable(() -> page = result.hasNext() ? page + 1 : 1)));
                });
    }

    private Mono<Void> synchronize(WechatBinding binding, Reply hint, BooleanSupplier active) {
        if (!active.getAsBoolean() || !approvedUserReply(hint)) return Mono.empty();
        String commentName = hint.getSpec().getCommentName();
        if (commentName == null) return Mono.empty();
        String targetReply = hint.getSpec().getQuoteReply();
        return links.forTarget(binding.getSpec().getGeneration(), commentName, targetReply)
                .filter(link -> link.getSpec().getState() == CommentLink.State.OPEN)
                .flatMap(link -> credentials
                        .read(binding.getSpec().getCredentialRef())
                        .flatMap(values -> comments.parent(binding, values.get("haloPat"), commentName)
                                .flatMap(parent -> comments.reply(
                                        binding,
                                        values.get("haloPat"),
                                        commentName,
                                        hint.getMetadata().getName()))
                                .filter(reply -> approvedUserReply(reply)
                                        && java.util.Objects.equals(
                                                targetReply, reply.getSpec().getQuoteReply()))
                                .flatMap(reply ->
                                        authors.moderator(reply.getSpec().getOwner()))
                                .filter(Boolean.TRUE::equals)
                                .flatMap(ignored -> bindings.current()
                                        .filter(current -> active.getAsBoolean()
                                                && current.getSpec().isEnabled()
                                                && current.getSpec().getGeneration()
                                                        == binding.getSpec().getGeneration())
                                        .flatMap(current -> links.changeState(
                                                binding.getSpec().getGeneration(),
                                                commentName,
                                                targetReply,
                                                CommentLink.State.ANSWERED,
                                                true)))))
                .then();
    }

    private static boolean approvedUserReply(Reply reply) {
        var spec = reply.getSpec();
        return reply.getMetadata().getDeletionTimestamp() == null
                && spec != null
                && Boolean.TRUE.equals(spec.getApproved())
                && spec.getOwner() != null
                && User.KIND.equals(spec.getOwner().getKind());
    }
}
