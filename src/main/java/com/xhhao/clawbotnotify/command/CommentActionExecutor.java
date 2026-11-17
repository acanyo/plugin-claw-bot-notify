package com.xhhao.clawbotnotify.command;

import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.CommentTargetReader;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Instant;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/** Idempotent local processing actions; they never mutate the website comment. */
@Component
public class CommentActionExecutor {
    private final ReactiveExtensionClient resources;
    private final CredentialStore credentials;
    private final HaloCommentReader comments;
    private final CommentTargetReader targets;
    private final CommentLinkRepository links;

    public CommentActionExecutor(
            ReactiveExtensionClient resources,
            CredentialStore credentials,
            HaloCommentReader comments,
            CommentLinkRepository links) {
        this.resources = resources;
        this.credentials = credentials;
        this.comments = comments;
        this.targets = new CommentTargetReader(comments);
        this.links = links;
    }

    public Mono<InboundCommand> process(WechatBinding binding, InboundCommand command) {
        var spec = command.getSpec();
        if (spec.getWriteState() != InboundCommand.WriteState.READY) return Mono.just(command);
        if (!binding.getSpec().isEnabled() || binding.getSpec().getGeneration() != spec.getGeneration())
            return finish(command, "BINDING_CHANGED");
        if (spec.getReceivedAt() == null
                || spec.getReceivedAt().isBefore(Instant.now().minusSeconds(86400))
                || spec.getReceivedAt().isAfter(Instant.now().plusSeconds(300)))
            return finish(command, "COMMAND_EXPIRED");
        return credentials
                .read(binding.getSpec().getCredentialRef())
                .flatMap(values -> new CommentTargetReader(comments)
                        .get(binding, values.get("haloPat"), spec.getCommentName(), spec.getTargetReplyName())
                        .switchIfEmpty(Mono.error(new IllegalStateException("COMMENT_DELETED"))))
                .flatMap(comment -> links.forTarget(
                                spec.getGeneration(), spec.getCommentName(), spec.getTargetReplyName())
                        .filter(link -> link.getSpec().getState() != CommentLink.State.DELETED)
                        .switchIfEmpty(Mono.error(new IllegalStateException("COMMENT_DELETED"))))
                .onErrorResume(error -> finish(command, safeCode(error)).then(Mono.empty()))
                .flatMap(link -> links.changeState(
                                spec.getGeneration(),
                                spec.getCommentName(),
                                spec.getTargetReplyName(),
                                spec.getType() == InboundCommand.Type.IGNORE
                                        ? CommentLink.State.IGNORED
                                        : CommentLink.State.ANSWERED,
                                false)
                        .then(finish(command, null))
                        .onErrorResume(error -> "COMMENT_DELETED".equals(error.getMessage())
                                ? finish(command, "COMMENT_DELETED")
                                : Mono.error(error)))
                .switchIfEmpty(Mono.defer(() -> resources.get(
                        InboundCommand.class, command.getMetadata().getName())));
    }

    private Mono<InboundCommand> finish(InboundCommand command, String error) {
        command.getSpec()
                .setWriteState(
                        error == null ? InboundCommand.WriteState.SUCCEEDED : InboundCommand.WriteState.REJECTED);
        command.getSpec().setErrorCode(error);
        return resources.update(command);
    }

    private static String safeCode(Throwable error) {
        String code = error.getMessage();
        return code != null && code.matches("[A-Z_]{1,80}") ? code : "HALO_AUTH_FAILED";
    }
}
