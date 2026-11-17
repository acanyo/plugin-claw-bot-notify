package com.xhhao.clawbotnotify.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.assistant.AiCommandService;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.draft.AssistantConfirmationExecutor;
import com.xhhao.clawbotnotify.assistant.review.AssistantPreviewReader;
import com.xhhao.clawbotnotify.channel.WechatReceiver;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.media.WechatMediaInbox;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.InboundCommand.Type;
import com.xhhao.clawbotnotify.model.InboundCommand.WriteState;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/** Acknowledge only after command execution state and its response task have both been persisted. */
@Component
public class InboundCommandService {
    private final ReactiveExtensionClient resources;
    private final InstanceIdentity instance;
    private final OutboundQueue queue;
    private final QuotedCommentResolver quotes;
    private final CredentialStore credentials;
    private final CommentReplyExecutor replies;
    private final CommentActionExecutor actions;
    private final AssistantSettings settings;
    private final AiCommandService assistant;
    private final AssistantConfirmationExecutor confirmations;
    private final AssistantPreviewReader previews;
    private final WechatMediaInbox media;

    public InboundCommandService(
            ReactiveExtensionClient resources,
            InstanceIdentity instance,
            OutboundQueue queue,
            QuotedCommentResolver quotes,
            CredentialStore credentials,
            CommentReplyExecutor replies,
            CommentActionExecutor actions,
            AssistantSettings settings,
            AiCommandService assistant,
            AssistantConfirmationExecutor confirmations,
            AssistantPreviewReader previews,
            WechatMediaInbox media) {
        this.resources = resources;
        this.instance = instance;
        this.queue = queue;
        this.quotes = quotes;
        this.credentials = credentials;
        this.replies = replies;
        this.actions = actions;
        this.settings = settings;
        this.assistant = assistant;
        this.confirmations = confirmations;
        this.previews = previews;
        this.media = media;
    }

    public Mono<Void> capture(WechatBinding binding, JsonNode message) {
        if (!WechatReceiver.isOwnerMessage(binding, message)) return Mono.empty();
        var text = new StringBuilder();
        for (var item : message.path("item_list"))
            text.append(item.path("text_item").path("text").asText());
        String body = text.toString();
        Mono<Selection> selection;
        if (WechatMediaInbox.hasImages(message) && !QuotedCommentResolver.hasQuote(message)) {
            selection = Mono.just(ai(
                    body.isBlank() ? Type.AI_MEDIA : Type.AI,
                    body.isBlank() ? "接收微信图片" : body + "\n此消息包含微信图片，使用 wechat_media_list 查看后再操作。"));
        } else if (!QuotedCommentResolver.hasQuote(message)
                && (body.strip().startsWith("AI ") || body.strip().startsWith("AI\n"))) {
            selection = Mono.just(ai(Type.AI, body.strip().substring(2).strip()));
        } else if (!QuotedCommentResolver.hasQuote(message)
                && (body.strip().equals("新对话")
                        || body.strip().equals("清空上下文")
                        || body.strip().equals("/clear"))) {
            selection = Mono.just(ai(Type.AI_RESET, body));
        } else if (QuotedCommentResolver.hasQuote(message)) {
            selection = quotes.resolve(binding, message)
                    .flatMap(target -> quoted(body, target))
                    .defaultIfEmpty(new Selection(Type.QUOTE_UNRESOLVED, 1, null, null, null, null, null, null, null));
        } else
            selection = Mono.justOrEmpty(ReadCommandParser.parse(body))
                    .map(parsed -> new Selection(
                            parsed.type(), parsed.page(), parsed.code(), null, null, null, null, null, null))
                    .switchIfEmpty(Mono.defer(() -> settings.get()
                            .filter(AssistantSettings.Config::isEnabled)
                            .map(config -> ai(Type.AI, body))
                            .onErrorReturn(ai(Type.AI, body))));
        return instance.get().flatMap(id -> {
            String name = "in-"
                    + StableIds.hash(
                            id,
                            "primary",
                            Long.toString(binding.getSpec().getGeneration()),
                            binding.getSpec().getBotId(),
                            message.path("message_id").asText());
            return resources
                    .fetch(InboundCommand.class, name)
                    .switchIfEmpty(selection.flatMap(selected -> create(binding, message, selected, name)))
                    .flatMap(record -> {
                        if (record.getSpec().getState() == InboundCommand.State.QUEUED) return Mono.empty();
                        return media.receive(binding, record, message)
                                .flatMap(captured -> process(binding, captured))
                                .flatMap(processed -> queue.enqueue(binding, "COMMAND", name, spec -> {
                                            spec.setCommandName(name);
                                            spec.setShortCode(
                                                    processed.getSpec().getShortCode());
                                            spec.setCommentName(
                                                    processed.getSpec().getCommentName());
                                            spec.setTargetReplyName(
                                                    processed.getSpec().getTargetReplyName());
                                            spec.setQuotedMessageId(
                                                    message.path("message_id").asText());
                                        })
                                        .then(Mono.defer(() -> {
                                            processed.getSpec().setState(InboundCommand.State.QUEUED);
                                            return resources.update(processed).then();
                                        })));
                    });
        });
    }

    private Mono<InboundCommand> process(WechatBinding binding, InboundCommand command) {
        return switch (command.getSpec().getType()) {
            case AI_MEDIA -> media.acknowledge(command);
            case AI_PREVIEW -> previews.process(binding, command);
            case AI, AI_RESET -> assistant.process(binding, command);
            case AI_CONFIRM, AI_APPROVE, AI_CANCEL -> confirmations.process(binding, command);
            case IGNORE, MARK_HANDLED -> actions.process(binding, command);
            default -> replies.process(binding, command);
        };
    }

    private Mono<InboundCommand> create(WechatBinding binding, JsonNode message, Selection selected, String name) {
        var record = new InboundCommand();
        var metadata = new Metadata();
        metadata.setName(name);
        record.setMetadata(metadata);
        var spec = new InboundCommand.Spec();
        spec.setGeneration(binding.getSpec().getGeneration());
        spec.setType(selected.type());
        spec.setPage(selected.page());
        spec.setShortCode(selected.code());
        spec.setCommentName(selected.commentName());
        spec.setTargetReplyName(selected.targetReplyName());
        spec.setApprovalOf(selected.approvalOf());
        spec.setQuotedMessageId(selected.messageId());
        spec.setReceivedAt(Instant.ofEpochMilli(message.path("create_time_ms").asLong()));
        record.setSpec(spec);
        Mono<Void> encrypted = Mono.empty();
        if (selected.type() == Type.AI
                || selected.type() == Type.AI_RESET
                || selected.type() == Type.AI_MEDIA
                || selected.type() == Type.AI_PREVIEW
                || isConfirmation(selected.type())) {
            spec.setPreviewOf(selected.approvalOf());
            spec.setApprovalOf(null);
            String ref = "clawbot-body-" + name.substring(3);
            spec.setBodyRef(ref);
            spec.setWriteState(WriteState.READY);
            String input = selected.body() == null ? "" : selected.body();
            if (input.isBlank()) {
                spec.setWriteState(WriteState.REJECTED);
                spec.setErrorCode("AI_BODY_INVALID");
                input = "";
            }
            String storedInput = input;
            Mono<Map<String, String>> content = selected.type() == Type.AI && selected.bodyRef() != null
                    ? resources
                            .get(InboundCommand.class, selected.bodyRef())
                            .flatMap(source -> credentials.read(source.getSpec().getBodyRef()))
                            .map(values -> Map.of("body", storedInput, "media", values.getOrDefault("media", "[]")))
                    : Mono.just(Map.of("body", storedInput));
            encrypted = content.flatMap(values -> credentials.create(ref, values))
                    .onErrorResume(error -> credentials.read(ref).then());
        }
        if (selected.type() == Type.IGNORE || selected.type() == Type.MARK_HANDLED)
            spec.setWriteState(WriteState.READY);
        if (selected.type() == Type.REPLY || selected.type() == Type.APPROVE_REPLY) {
            spec.setWriteState(WriteState.READY);
            if (selected.bodyRef() != null) spec.setBodyRef(selected.bodyRef());
            else if (selected.body() == null
                    || selected.body().isBlank()
                    || selected.body().codePointCount(0, selected.body().length()) > 2000) {
                spec.setWriteState(WriteState.REJECTED);
                spec.setErrorCode("REPLY_BODY_INVALID");
            } else {
                String ref = "clawbot-body-" + name.substring(3);
                spec.setBodyRef(ref);
                encrypted = credentials
                        .create(ref, Map.of("body", selected.body()))
                        .onErrorResume(error -> credentials.read(ref).then());
            }
        }
        return encrypted
                .then(resources.create(record))
                .onErrorResume(
                        error -> resources.fetch(InboundCommand.class, name).switchIfEmpty(Mono.error(error)));
    }

    private static Selection ai(Type type, String body) {
        return new Selection(type, 1, null, null, null, body, null, null, null);
    }

    private static boolean isConfirmation(Type type) {
        return type == Type.AI_CONFIRM || type == Type.AI_APPROVE || type == Type.AI_CANCEL;
    }

    private Mono<Selection> quoted(String body, QuotedCommentResolver.Target target) {
        Mono<Selection> preview = target.commandName() == null
                ? Mono.empty()
                : resources
                        .fetch(InboundCommand.class, target.commandName())
                        .filter(source -> (source.getSpec().getType() == Type.AI
                                        || source.getSpec().getType() == Type.AI_MEDIA)
                                && source.getSpec().getBodyRef() != null)
                        .flatMap(source -> source.getSpec().getCleanedAt() != null
                                ? Mono.just(Map.<String, String>of())
                                : credentials
                                        .read(source.getSpec().getBodyRef())
                                        .onErrorReturn(Map.of()))
                        .map(values -> {
                            String action = body.strip();
                            if (values.containsKey("media") && !values.containsKey("proposal"))
                                return new Selection(
                                        Type.AI,
                                        1,
                                        null,
                                        null,
                                        null,
                                        action.startsWith("AI ")
                                                ? action.substring(3).strip()
                                                : action,
                                        target.commandName(),
                                        null,
                                        target.messageId());
                            var part = java.util.regex.Pattern.compile("查看第\\s*([1-9]\\d{0,2})\\s*段")
                                    .matcher(action);
                            boolean partRequest = part.matches();
                            var diffPart = java.util.regex.Pattern.compile("查看差异第\\s*([1-9]\\d{0,2})\\s*段")
                                    .matcher(action);
                            boolean diffRequest = diffPart.matches();
                            int page = partRequest
                                    ? Integer.parseInt(part.group(1))
                                    : diffRequest
                                            ? -Integer.parseInt(diffPart.group(1))
                                            : action.equals("查看差异") ? 0 : 1;
                            Type type = !values.containsKey("proposal")
                                    ? Type.INVALID
                                    : switch (action) {
                                        case "确认发布", "确认保存", "确认发送", "确认执行" -> Type.AI_CONFIRM;
                                        case "确认审核并发送" -> Type.AI_APPROVE;
                                        case "取消" -> Type.AI_CANCEL;
                                        default ->
                                            partRequest || diffRequest || action.equals("查看差异")
                                                    ? Type.AI_PREVIEW
                                                    : action.matches("(?:确认全文(?:保存|发布)|确认图片操作) [a-f0-9]{12}")
                                                            ? Type.AI_CONFIRM
                                                            : action.startsWith("修改 ") || action.startsWith("修改\n")
                                                                    ? Type.AI
                                                                    : Type.INVALID;
                                    };
                            return new Selection(
                                    type,
                                    page,
                                    null,
                                    target.commentName(),
                                    target.targetReplyName(),
                                    type == Type.AI ? action.substring(2).strip() : body,
                                    null,
                                    target.commandName(),
                                    target.messageId());
                        });
        return preview.switchIfEmpty(Mono.defer(() -> {
            String action = body.strip();
            if ((action.startsWith("AI ") || action.startsWith("AI\n")) && target.commentName() != null)
                return Mono.just(new Selection(
                        Type.AI,
                        1,
                        null,
                        target.commentName(),
                        target.targetReplyName(),
                        action.substring(2).strip(),
                        null,
                        null,
                        target.messageId()));
            if (java.util.Set.of("确认发布", "确认保存", "确认发送", "确认执行", "确认审核并发送", "取消")
                            .contains(action)
                    || action.matches("(?:确认全文(?:保存|发布)|确认图片操作) [a-f0-9]{12}")
                    || action.startsWith("查看第")
                    || action.startsWith("查看差异")
                    || action.startsWith("修改 ")
                    || action.startsWith("修改\n"))
                return Mono.just(
                        new Selection(Type.INVALID, 1, null, null, null, null, null, null, target.messageId()));
            if (target.commentName() == null)
                return Mono.just(
                        new Selection(Type.INVALID, 1, null, null, null, null, null, null, target.messageId()));
            return quotedComment(body, target);
        }));
    }

    private Mono<Selection> quotedComment(String body, QuotedCommentResolver.Target target) {
        String command = body.strip();
        if (command.equals("忽略") || command.equals("标记已处理"))
            return Mono.just(new Selection(
                    command.equals("忽略") ? Type.IGNORE : Type.MARK_HANDLED,
                    1,
                    null,
                    target.commentName(),
                    target.targetReplyName(),
                    null,
                    null,
                    null,
                    target.messageId()));
        if (command.equals("查看") || command.equals("详情"))
            return Mono.just(new Selection(
                    Type.VIEW,
                    1,
                    null,
                    target.commentName(),
                    target.targetReplyName(),
                    null,
                    null,
                    null,
                    target.messageId()));
        if (command.equals("审核并回复") || command.startsWith("审核并回复 ") || command.startsWith("审核并回复\n")) {
            String replacement = command.substring("审核并回复".length()).stripLeading();
            if (target.commandName() != null)
                return resources
                        .fetch(InboundCommand.class, target.commandName())
                        .filter(draft -> draft.getSpec().getWriteState() != null
                                && target.commentName().equals(draft.getSpec().getCommentName())
                                && java.util.Objects.equals(
                                        target.targetReplyName(),
                                        draft.getSpec().getTargetReplyName()))
                        .map(draft -> new Selection(
                                Type.APPROVE_REPLY,
                                1,
                                null,
                                target.commentName(),
                                target.targetReplyName(),
                                replacement.isEmpty() ? null : replacement,
                                replacement.isEmpty() ? draft.getSpec().getBodyRef() : null,
                                target.commandName(),
                                target.messageId()))
                        .defaultIfEmpty(new Selection(
                                Type.APPROVE_REPLY,
                                1,
                                null,
                                target.commentName(),
                                target.targetReplyName(),
                                replacement,
                                null,
                                null,
                                target.messageId()));
            return Mono.just(new Selection(
                    Type.APPROVE_REPLY,
                    1,
                    null,
                    target.commentName(),
                    target.targetReplyName(),
                    replacement,
                    null,
                    null,
                    target.messageId()));
        }
        return Mono.just(new Selection(
                Type.REPLY,
                1,
                null,
                target.commentName(),
                target.targetReplyName(),
                body,
                null,
                null,
                target.messageId()));
    }

    private record Selection(
            Type type,
            int page,
            String code,
            String commentName,
            String targetReplyName,
            String body,
            String bodyRef,
            String approvalOf,
            String messageId) {}
}
