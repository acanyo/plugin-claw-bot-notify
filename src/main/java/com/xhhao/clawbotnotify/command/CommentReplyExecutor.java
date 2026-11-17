package com.xhhao.clawbotnotify.command;

import static run.halo.app.extension.index.query.Queries.equal;

import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.halo.CommentTargetReader;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.halo.HaloWriteException;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.InboundCommand.WriteState;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Instant;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** A claimed write is never repeated, including after a lost HTTP response or plugin restart. */
@Component
public class CommentReplyExecutor {
    private final ReactiveExtensionClient resources;
    private final CredentialStore credentials;
    private final HaloCommentReader comments;
    private final CommentTargetReader targets;
    private final HaloIdentityVerifier halo;
    private final CommentLinkRepository links;

    public CommentReplyExecutor(
            ReactiveExtensionClient resources,
            CredentialStore credentials,
            HaloCommentReader comments,
            HaloIdentityVerifier halo,
            CommentLinkRepository links) {
        this.resources = resources;
        this.credentials = credentials;
        this.comments = comments;
        this.targets = new CommentTargetReader(comments);
        this.halo = halo;
        this.links = links;
    }

    public Mono<InboundCommand> process(WechatBinding binding, InboundCommand command) {
        var spec = command.getSpec();
        if (spec.getWriteState() != WriteState.READY)
            return updateDraft(command).then(markAnswered(command)).thenReturn(command);
        if (!binding.getSpec().isEnabled() || binding.getSpec().getGeneration() != spec.getGeneration())
            return finish(command, WriteState.REJECTED, "BINDING_CHANGED");
        if (spec.getReceivedAt() == null
                || spec.getReceivedAt().isBefore(Instant.now().minusSeconds(86400))
                || spec.getReceivedAt().isAfter(Instant.now().plusSeconds(300)))
            return finish(command, WriteState.REJECTED, "COMMAND_EXPIRED");
        return credentials
                .read(spec.getBodyRef())
                .map(values -> values.getOrDefault("body", ""))
                .flatMap(body -> {
                    if (body.isBlank() || body.codePointCount(0, body.length()) > 2000)
                        return finish(command, WriteState.REJECTED, "REPLY_BODY_INVALID");
                    return credentials
                            .read(binding.getSpec().getCredentialRef())
                            .flatMap(values -> targets.get(
                                            binding,
                                            values.get("haloPat"),
                                            spec.getCommentName(),
                                            spec.getTargetReplyName())
                                    .switchIfEmpty(Mono.error(new IllegalStateException("COMMENT_DELETED")))
                                    .flatMap(comment -> {
                                        if (!comment.approved()
                                                && spec.getType() != InboundCommand.Type.APPROVE_REPLY
                                                && spec.getType() != InboundCommand.Type.AI_APPROVE)
                                            return finish(command, WriteState.AWAITING_APPROVAL, "APPROVAL_REQUIRED");
                                        return dispatch(binding, command, comment, body, values.get("haloPat"));
                                    }));
                })
                .onErrorResume(error -> spec.getWriteState() == WriteState.READY
                        ? finish(command, WriteState.REJECTED, safeCode(error))
                        : Mono.error(error));
    }

    private Mono<InboundCommand> dispatch(
            WechatBinding binding,
            InboundCommand command,
            CommentTargetReader.Target comment,
            String body,
            String token) {
        return claimApproval(command).flatMap(allowed -> {
            if (!allowed) return finish(command, WriteState.REJECTED, "DRAFT_ALREADY_CONFIRMED");
            // Finish all validation and rendering before committing EXECUTING.
            String html = MessageFormatting.replyHtml(body);
            command.getSpec().setWriteState(WriteState.EXECUTING);
            command.getSpec().setErrorCode(null);
            return resources
                    .update(command)
                    .flatMap(claimed -> (claimed.getSpec().getTargetReplyName() == null
                                    ? halo.postReply(token, claimed.getSpec().getCommentName(), body, html)
                                    : halo.postReply(
                                            token,
                                            claimed.getSpec().getCommentName(),
                                            claimed.getSpec().getTargetReplyName(),
                                            body,
                                            html))
                            .map(response -> {
                                String replyName =
                                        response.path("metadata").path("name").asText();
                                if (replyName.isBlank()
                                        || !claimed.getSpec()
                                                .getCommentName()
                                                .equals(response.path("spec")
                                                        .path("commentName")
                                                        .asText())
                                        || !java.util.Objects.equals(
                                                claimed.getSpec().getTargetReplyName(),
                                                response.path("spec")
                                                                .path("quoteReply")
                                                                .isTextual()
                                                        ? response.path("spec")
                                                                .path("quoteReply")
                                                                .asText()
                                                        : null))
                                    throw new HaloWriteException("HALO_WRITE_UNKNOWN", false);
                                return new Outcome(WriteState.SUCCEEDED, replyName, null);
                            })
                            // Only classify the HTTP attempt; bookkeeping cannot turn a successful reply into a retry.
                            .onErrorResume(error -> {
                                if (error instanceof HaloWriteException failure && failure.rejected())
                                    return Mono.just(new Outcome(WriteState.REJECTED, null, failure.getMessage()));
                                if ((claimed.getSpec().getType() == InboundCommand.Type.APPROVE_REPLY
                                                || claimed.getSpec().getType() == InboundCommand.Type.AI_APPROVE)
                                        && !comment.approved())
                                    return targets.get(
                                                    binding,
                                                    token,
                                                    claimed.getSpec().getCommentName(),
                                                    claimed.getSpec().getTargetReplyName())
                                            .map(current -> new Outcome(
                                                    current.approved() ? WriteState.PARTIAL : WriteState.UNKNOWN,
                                                    null,
                                                    "HALO_WRITE_UNKNOWN"))
                                            .defaultIfEmpty(new Outcome(WriteState.UNKNOWN, null, "HALO_WRITE_UNKNOWN"))
                                            .onErrorReturn(new Outcome(WriteState.UNKNOWN, null, "HALO_WRITE_UNKNOWN"));
                                return Mono.just(new Outcome(WriteState.UNKNOWN, null, "HALO_WRITE_UNKNOWN"));
                            })
                            .flatMap(outcome -> {
                                claimed.getSpec().setReplyName(outcome.replyName());
                                return finish(claimed, outcome.state(), outcome.code())
                                        .flatMap(saved -> updateDraft(saved)
                                                .then(markAnswered(saved))
                                                .thenReturn(saved));
                            }));
        });
    }

    private Mono<Boolean> claimApproval(InboundCommand command) {
        String source = command.getSpec().getApprovalOf();
        if (source == null) return Mono.just(true);
        return resources.get(InboundCommand.class, source).flatMap(draft -> {
            var spec = draft.getSpec();
            if (spec.getWriteState() != WriteState.AWAITING_APPROVAL
                    || spec.getGeneration() != command.getSpec().getGeneration()
                    || !spec.getCommentName().equals(command.getSpec().getCommentName())
                    || !java.util.Objects.equals(
                            spec.getTargetReplyName(), command.getSpec().getTargetReplyName())) return Mono.just(false);
            if (spec.getApprovalCommand() != null)
                return Mono.just(
                        spec.getApprovalCommand().equals(command.getMetadata().getName()));
            spec.setApprovalCommand(command.getMetadata().getName());
            return resources.update(draft).thenReturn(true);
        });
    }

    private Mono<Void> updateDraft(InboundCommand command) {
        if (command.getSpec().getApprovalOf() == null) return Mono.empty();
        return resources
                .get(InboundCommand.class, command.getSpec().getApprovalOf())
                .flatMap(draft -> {
                    if (!command.getMetadata().getName().equals(draft.getSpec().getApprovalCommand()))
                        return Mono.empty();
                    if (draft.getSpec().getWriteState() == command.getSpec().getWriteState()
                            && java.util.Objects.equals(
                                    draft.getSpec().getReplyName(),
                                    command.getSpec().getReplyName())
                            && java.util.Objects.equals(
                                    draft.getSpec().getErrorCode(),
                                    command.getSpec().getErrorCode())) return Mono.empty();
                    draft.getSpec().setWriteState(command.getSpec().getWriteState());
                    draft.getSpec().setReplyName(command.getSpec().getReplyName());
                    draft.getSpec().setErrorCode(command.getSpec().getErrorCode());
                    return resources.update(draft).then();
                });
    }

    private Mono<Void> markAnswered(InboundCommand command) {
        if (command.getSpec().getWriteState() != WriteState.SUCCEEDED) return Mono.empty();
        return links.answered(
                command.getSpec().getGeneration(),
                command.getSpec().getCommentName(),
                command.getSpec().getTargetReplyName());
    }

    public Mono<Void> recoverInterrupted() {
        return resources
                .listBy(
                        InboundCommand.class,
                        ListOptions.builder()
                                .fieldQuery(equal("spec.writeState", WriteState.EXECUTING.name()))
                                .build(),
                        PageRequestImpl.ofSize(100))
                .flatMapMany(page -> Flux.fromIterable(page.getItems()))
                .concatMap(command -> finish(command, WriteState.UNKNOWN, "STOPPED_DURING_WRITE"))
                .then();
    }

    private Mono<InboundCommand> finish(InboundCommand command, WriteState state, String code) {
        command.getSpec().setWriteState(state);
        command.getSpec().setErrorCode(code);
        return resources.update(command);
    }

    private static String safeCode(Throwable error) {
        String code = error.getMessage();
        return code != null && code.matches("[A-Z_]{1,80}") ? code : "HALO_AUTH_FAILED";
    }

    private record Outcome(WriteState state, String replyName, String code) {}
}
