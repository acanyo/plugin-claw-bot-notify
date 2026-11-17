package com.xhhao.clawbotnotify.assistant.draft;

import com.xhhao.clawbotnotify.assistant.AssistantAuthority;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.assistant.cli.HaloCliOutput;
import com.xhhao.clawbotnotify.command.CommentReplyExecutor;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.InboundCommand.WriteState;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/** Confirmation never invokes a model; it dispatches the exact encrypted proposal after a single-use claim. */
@Component
public class AssistantConfirmationExecutor {
    private final ReactiveExtensionClient resources;
    private final CredentialStore secrets;
    private final AssistantDrafts drafts;
    private final AssistantAuthority authority;
    private final AssistantSettings settings;
    private final AssistantTools tools;
    private final CommentReplyExecutor replies;

    public AssistantConfirmationExecutor(
            ReactiveExtensionClient resources,
            CredentialStore secrets,
            AssistantDrafts drafts,
            AssistantAuthority authority,
            AssistantSettings settings,
            AssistantTools tools,
            CommentReplyExecutor replies) {
        this.resources = resources;
        this.secrets = secrets;
        this.drafts = drafts;
        this.authority = authority;
        this.settings = settings;
        this.tools = tools;
        this.replies = replies;
    }

    public Mono<InboundCommand> process(WechatBinding binding, InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef()).flatMap(data -> {
            if (data.containsKey("aiResponse"))
                return save(
                        command, data.get("aiResponse"), WriteState.valueOf(data.get("aiState")), data.get("aiError"));
            if (command.getSpec().getWriteState() == WriteState.UNKNOWN)
                return failure(command, new IllegalStateException("AI_INTERRUPTED"));
            if (command.getSpec().getWriteState() != WriteState.READY) return Mono.just(command);
            Instant received = command.getSpec().getReceivedAt();
            if (received == null
                    || received.isBefore(Instant.now().minusSeconds(86400))
                    || received.isAfter(Instant.now().plusSeconds(300)))
                return save(command, "确认指令已过期，请重新生成预览。", WriteState.REJECTED, "AI_PREVIEW_EXPIRED");
            return authority
                    .check(binding)
                    .then(settings.get())
                    .flatMap(config -> {
                        if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                        if (!config.isAllowWrites() && command.getSpec().getType() != InboundCommand.Type.AI_CANCEL)
                            return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                        return drafts.load(binding, command.getSpec().getPreviewOf())
                                .flatMap(draft -> {
                                    if (command.getSpec().getType() != InboundCommand.Type.AI_CANCEL
                                            && !AssistantDrafts.confirmation(draft)
                                                    .equals(data.getOrDefault("body", "")
                                                            .strip()))
                                        return Mono.error(new IllegalStateException("AI_CONFIRMATION_MISMATCH"));
                                    if (draft.approval()
                                            && command.getSpec().getType() == InboundCommand.Type.AI_CONFIRM)
                                        return Mono.error(new IllegalStateException("AI_APPROVAL_REQUIRED"));
                                    return (command.getSpec().getType() == InboundCommand.Type.AI_CANCEL
                                                    ? Mono.<Void>empty()
                                                    : drafts.verifyAuthor(draft))
                                            .then(drafts.claim(
                                                    binding,
                                                    command.getSpec().getPreviewOf(),
                                                    command.getMetadata().getName()));
                                })
                                .flatMap(draft -> execute(binding, command, draft));
                    })
                    .onErrorResume(error -> failure(command, error));
        });
    }

    private Mono<InboundCommand> execute(WechatBinding binding, InboundCommand command, AssistantDrafts.Draft draft) {
        if (command.getSpec().getType() == InboundCommand.Type.AI_CANCEL)
            return save(command, "已取消这份预览，未写入 Halo。", WriteState.SUCCEEDED, null);
        command.getSpec().setStartedAt(Instant.now());
        command.getSpec().setWriteState(WriteState.EXECUTING);
        return resources
                .update(command)
                .flatMap(claimed -> secrets.update(claimed.getSpec().getBodyRef(), values -> {
                            values.put("confirmedProposal", drafts.encode(draft));
                            if (draft.tool().equals("comment_reply")) {
                                values.put("confirmationText", values.getOrDefault("body", ""));
                                values.put("body", String.valueOf(draft.input().get("content")));
                            }
                        })
                        .then(Mono.defer(() -> draft.tool().equals("comment_reply")
                                ? reply(binding, claimed)
                                : publish(binding, claimed, draft))));
    }

    private Mono<InboundCommand> publish(WechatBinding binding, InboundCommand command, AssistantDrafts.Draft draft) {
        return tools.executeConfirmed(binding, command).flatMap(result -> {
            if (com.xhhao.clawbotnotify.media.AssistantMediaTools.supports(draft.tool()))
                return save(
                        command,
                        "**图片操作已完成**\n\n" + MessageFormatting.truncate(HaloCliOutput.pretty(result), 2400),
                        WriteState.SUCCEEDED,
                        null);
            if (draft.tool().equals("halo_cli"))
                return save(
                        command,
                        "**CLI 操作已执行**\n\n" + MessageFormatting.truncate(HaloCliOutput.pretty(result), 2400),
                        WriteState.SUCCEEDED,
                        null);
            String name = result instanceof Map<?, ?> resource ? String.valueOf(resource.get("name")) : "";
            String label =
                    draft.tool().equals("post_publish_draft") || draft.tool().equals("moment_create") ? "已发布" : "草稿已保存";
            return save(
                    command,
                    "**" + label + "**\n资源：" + name
                            + (draft.tool().equals("moment_create") ? "\n发布身份：" + draft.authorLabel() : ""),
                    WriteState.SUCCEEDED,
                    null);
        });
    }

    private Mono<InboundCommand> reply(WechatBinding binding, InboundCommand command) {
        // The existing reply executor owns PAT validation, thread targeting, moderation and uncertain outcomes.
        return resources
                .get(InboundCommand.class, command.getSpec().getPreviewOf())
                .flatMap(source -> {
                    command.getSpec().setCommentName(source.getSpec().getCommentName());
                    command.getSpec().setTargetReplyName(source.getSpec().getTargetReplyName());
                    command.getSpec().setWriteState(WriteState.READY);
                    return replies.process(binding, command);
                })
                .flatMap(result -> {
                    WriteState state = result.getSpec().getWriteState();
                    String text = state == WriteState.SUCCEEDED
                            ? "**回复已发布**\n资源：" + result.getSpec().getReplyName()
                            : state == WriteState.AWAITING_APPROVAL
                                    ? "目标评论现为待审核，未发布回复。请重新引用原评论发送“AI 回复”并确认审核。"
                                    : "回复未能确认成功，请到 Halo 核查。不会自动重复发送。";
                    return save(result, text, state, result.getSpec().getErrorCode());
                });
    }

    private Mono<InboundCommand> failure(InboundCommand command, Throwable error) {
        return resources
                .get(InboundCommand.class, command.getMetadata().getName())
                .flatMap(current -> failureCurrent(current, error));
    }

    private Mono<InboundCommand> failureCurrent(InboundCommand command, Throwable error) {
        return secrets.read(command.getSpec().getBodyRef()).flatMap(values -> {
            String code = AssistantTools.safeCode(error);
            boolean dispatched =
                    values.containsKey("mutation") || command.getSpec().getWriteState() == WriteState.UNKNOWN;
            boolean known =
                    values.containsKey("mutationResult") || command.getSpec().getReplyName() != null;
            dispatched |= known;
            return save(
                    command,
                    dispatched
                            ? (known ? "写入已完成，但后续记录失败。" : "执行结果未知，请先到 Halo 核查，不会重复执行。") + "\n资源："
                                    + values.getOrDefault(
                                            "mutationName",
                                            command.getSpec().getReplyName() == null
                                                    ? "见运行记录"
                                                    : command.getSpec().getReplyName())
                                    + (values.containsKey("mediaCompleted")
                                            ? "\n已上传附件：" + values.get("mediaCompleted") + "；内容操作请单独核查。"
                                            : "")
                            : "未执行（" + code + "）。请检查预览是否过期、已修改或确认过，再重新生成预览。",
                    dispatched ? known ? WriteState.PARTIAL : WriteState.UNKNOWN : WriteState.REJECTED,
                    code);
        });
    }

    private Mono<InboundCommand> save(InboundCommand command, String text, WriteState state, String error) {
        return secrets.update(command.getSpec().getBodyRef(), values -> {
                    values.put("aiResponse", text);
                    values.put("aiState", state.name());
                    if (error != null) values.put("aiError", error);
                    else values.remove("aiError");
                })
                .then(Mono.defer(() -> {
                    command.getSpec().setWriteState(state);
                    command.getSpec().setErrorCode(error);
                    if (command.getSpec().getStartedAt() != null
                            && command.getSpec().getFinishedAt() == null) {
                        command.getSpec().setFinishedAt(Instant.now());
                        command.getSpec()
                                .setDurationMs(Duration.between(
                                                command.getSpec().getStartedAt(),
                                                command.getSpec().getFinishedAt())
                                        .toMillis());
                    }
                    return resources.update(command);
                }));
    }
}
