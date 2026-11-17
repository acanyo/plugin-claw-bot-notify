package com.xhhao.clawbotnotify.command;

import static run.halo.app.extension.index.query.Queries.*;

import com.xhhao.clawbotnotify.api.CommandDetail;
import com.xhhao.clawbotnotify.api.CommandView;
import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.halo.CommentTargetReader;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Condition;

@Component
public class CommandRecordService {
    private final ReactiveExtensionClient resources;
    private final BindingService bindings;
    private final CommentLinkRepository links;
    private final CredentialStore credentials;
    private final HaloCommentReader comments;
    private final CommentTargetReader targets;

    public CommandRecordService(
            ReactiveExtensionClient resources,
            BindingService bindings,
            CommentLinkRepository links,
            CredentialStore credentials,
            HaloCommentReader comments) {
        this.resources = resources;
        this.bindings = bindings;
        this.links = links;
        this.credentials = credentials;
        this.comments = comments;
        this.targets = new CommentTargetReader(comments);
    }

    public Mono<CommandView.Page> list(int page, int size, String type, String result) {
        if (page < 1 || size < 1 || size > 100) return Mono.error(badRequest("INVALID_PAGINATION"));
        Condition query = isNull("spec.cleanedAt");
        try {
            if (type != null && !type.isBlank())
                query = query.and(
                        equal("spec.type", InboundCommand.Type.valueOf(type).name()));
            if ("NEEDS_REVIEW".equals(result))
                query = query.and(in("spec.writeState", "UNKNOWN", "PARTIAL", "EXECUTING"));
            else if (result != null && !result.isBlank())
                query = query.and(equal(
                        "spec.writeState",
                        InboundCommand.WriteState.valueOf(result).name()));
        } catch (IllegalArgumentException error) {
            return Mono.error(badRequest("INVALID_COMMAND_FILTER"));
        }
        return resources
                .listBy(
                        InboundCommand.class,
                        ListOptions.builder().fieldQuery(query).build(),
                        PageRequestImpl.of(
                                page,
                                size,
                                Sort.by(Sort.Direction.DESC, "metadata.creationTimestamp", "metadata.name")))
                .flatMap(rows -> Flux.fromIterable(rows.getItems())
                        .concatMap(this::view)
                        .collectList()
                        .map(items -> new CommandView.Page(page, size, rows.getTotal(), items)));
    }

    public Mono<CommandDetail> detail(String username, String id) {
        if (id == null || !id.matches("in-[a-f0-9]{64}")) return Mono.error(notFound());
        return bindings.owned(username)
                .flatMap(binding -> resources
                        .fetch(InboundCommand.class, id)
                        .switchIfEmpty(Mono.error(notFound()))
                        .flatMap(command -> view(command).flatMap(row -> {
                            if (binding.getSpec().getGeneration()
                                    != command.getSpec().getGeneration())
                                return Mono.just(
                                        new CommandDetail(row, null, null, null, null, "HISTORICAL_GENERATION"));
                            if (command.getSpec().getCleanedAt() != null)
                                return Mono.just(new CommandDetail(row, null, null, null, null, "HISTORY_CLEANED"));
                            boolean ai = command.getSpec().getType() == InboundCommand.Type.AI
                                    || command.getSpec().getType() == InboundCommand.Type.AI_RESET
                                    || command.getSpec().getType() == InboundCommand.Type.AI_PREVIEW
                                    || command.getSpec().getType() == InboundCommand.Type.AI_MEDIA
                                    || command.getSpec().getType() == InboundCommand.Type.AI_CONFIRM
                                    || command.getSpec().getType() == InboundCommand.Type.AI_APPROVE
                                    || command.getSpec().getType() == InboundCommand.Type.AI_CANCEL;
                            if (ai)
                                return credentials
                                        .read(command.getSpec().getBodyRef())
                                        .map(values -> new CommandDetail(
                                                row,
                                                values.getOrDefault("confirmationText", values.get("body")),
                                                null,
                                                null,
                                                cliResponse(values),
                                                null,
                                                preview(values)))
                                        .onErrorReturn(
                                                new CommandDetail(row, null, null, null, null, "BODY_UNAVAILABLE"));
                            var body = command.getSpec().getBodyRef() == null
                                    ? Mono.just(new Body(null, null))
                                    : credentials
                                            .read(command.getSpec().getBodyRef())
                                            .map(values -> new Body(values.get("body"), null))
                                            .onErrorReturn(new Body(null, "BODY_UNAVAILABLE"));
                            return Mono.zip(body, context(binding, command, row.commentName(), row.targetReplyName()))
                                    .map(tuple -> new CommandDetail(
                                            row,
                                            tuple.getT1().markdown(),
                                            tuple.getT2().comment(),
                                            tuple.getT2().targetReply(),
                                            tuple.getT2().reply(),
                                            tuple.getT1().error() != null
                                                    ? tuple.getT1().error()
                                                    : tuple.getT2().error()));
                        })));
    }

    private static com.xhhao.clawbotnotify.assistant.review.DraftReview.View preview(Map<String, String> values) {
        String proposal = values.getOrDefault("confirmedProposal", values.get("proposal"));
        if (proposal == null) return null;
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var draft = json.readValue(proposal, com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts.Draft.class);
            if (!java.util.Set.of("post_create_draft", "post_update_draft", "post_publish_draft")
                    .contains(draft.tool())) return null;
            Map<String, Object> before = values.containsKey("proposalBefore")
                    ? json.readValue(
                            values.get("proposalBefore"),
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    : Map.of();
            return com.xhhao.clawbotnotify.assistant.review.DraftReview.view(draft, before);
        } catch (Exception error) {
            return null;
        }
    }

    private static String cliResponse(Map<String, String> values) {
        String response = values.get("aiResponse");
        String proposal = values.getOrDefault("confirmedProposal", values.get("proposal"));
        if (proposal == null) return response;
        try {
            var draft = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(proposal, com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts.Draft.class);
            if (!draft.tool().equals("halo_cli")) return response;
            return (response == null ? "" : response) + "\n\n完整 CLI 参数与标准输入（敏感字段已隐藏）：\n\n"
                    + com.xhhao.clawbotnotify.assistant.cli.HaloCliOutput.pretty(
                            com.xhhao.clawbotnotify.assistant.cli.HaloCliOutput.proposal(draft.input()));
        } catch (Exception error) {
            return response;
        }
    }

    private Mono<Context> context(WechatBinding binding, InboundCommand command, String target, String targetReply) {
        var spec = command.getSpec();
        if (target == null) return Mono.just(new Context(null, null, null, null));
        if (!binding.getSpec().isEnabled()) return Mono.just(new Context(null, null, null, "BINDING_DISABLED"));
        return credentials
                .read(binding.getSpec().getCredentialRef())
                .flatMap(values -> {
                    String token = values.get("haloPat");
                    return targets.get(binding, token, target, targetReply)
                            .flatMap(comment -> {
                                String raw = MessageFormatting.truncate(
                                        comment.comment().getSpec().getRaw() == null
                                                ? ""
                                                : comment.comment().getSpec().getRaw(),
                                        3000);
                                String targetBody = comment.reply() == null
                                        ? null
                                        : MessageFormatting.truncate(
                                                comment.reply().getSpec().getRaw() == null
                                                        ? ""
                                                        : comment.reply()
                                                                .getSpec()
                                                                .getRaw(),
                                                3000);
                                if (spec.getReplyName() == null)
                                    return Mono.just(new Context(raw, targetBody, null, null));
                                return comments.reply(binding, token, target, spec.getReplyName())
                                        .map(reply -> new Context(
                                                raw,
                                                targetBody,
                                                MessageFormatting.truncate(
                                                        reply.getSpec().getRaw() == null
                                                                ? ""
                                                                : reply.getSpec()
                                                                        .getRaw(),
                                                        3000),
                                                null))
                                        .defaultIfEmpty(new Context(raw, targetBody, null, "REPLY_DELETED"));
                            })
                            .defaultIfEmpty(new Context(null, null, null, "COMMENT_DELETED"));
                })
                .onErrorReturn(new Context(null, null, null, "HALO_CONTENT_UNAVAILABLE"));
    }

    private Mono<CommandView> view(InboundCommand command) {
        var spec = command.getSpec();
        String receipt = "out-"
                + StableIds.hash(
                        "primary",
                        Long.toString(spec.getGeneration()),
                        "COMMAND",
                        command.getMetadata().getName());
        var outbound =
                resources.fetch(OutboundTask.class, receipt).map(Optional::of).defaultIfEmpty(Optional.empty());
        var link = spec.getCommentName() == null
                ? links.forCode(spec.getGeneration(), spec.getShortCode() == null ? "" : spec.getShortCode())
                : links.forTarget(spec.getGeneration(), spec.getCommentName(), spec.getTargetReplyName());
        return Mono.zip(outbound, link.map(Optional::of).defaultIfEmpty(Optional.empty()))
                .map(tuple -> {
                    var task = tuple.getT1().map(OutboundTask::getSpec).orElse(null);
                    var target = tuple.getT2().map(CommentLink::getSpec).orElse(null);
                    boolean review = spec.getWriteState() == InboundCommand.WriteState.UNKNOWN
                            || spec.getWriteState() == InboundCommand.WriteState.PARTIAL
                            || spec.getWriteState() == InboundCommand.WriteState.EXECUTING;
                    return new CommandView(
                            command.getMetadata().getName(),
                            spec.getGeneration(),
                            spec.getType().name(),
                            spec.getState().name(),
                            spec.getWriteState() == null
                                    ? null
                                    : spec.getWriteState().name(),
                            target == null ? spec.getCommentName() : target.getCommentName(),
                            target == null ? spec.getTargetReplyName() : target.getTargetReplyName(),
                            target == null ? spec.getShortCode() : target.getShortCode(),
                            target == null ? null : target.getState().name(),
                            spec.getReplyName(),
                            spec.getReceivedAt(),
                            command.getMetadata().getCreationTimestamp(),
                            spec.getErrorCode(),
                            task == null ? null : task.getState().name(),
                            task == null ? null : task.getErrorCode(),
                            review,
                            spec.getStartedAt(),
                            spec.getFinishedAt(),
                            spec.getDurationMs(),
                            spec.getModelDurationMs(),
                            spec.getCliDurationMs(),
                            spec.getModelName(),
                            spec.getCleanedAt());
                });
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "COMMAND_NOT_FOUND");
    }

    private record Body(String markdown, String error) {}

    private record Context(String comment, String targetReply, String reply, String error) {}
}
