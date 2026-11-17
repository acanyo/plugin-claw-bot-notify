package com.xhhao.clawbotnotify.assistant.draft;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.HaloCliOutput;
import com.xhhao.clawbotnotify.assistant.review.DraftReview;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.halo.MomentAuthors;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/** Proposals and their single-use claims live in encrypted command bodies, never in model history alone. */
@Component
public class AssistantDrafts {
    private final ReactiveExtensionClient resources;
    private final CredentialStore secrets;
    private final AssistantSettings settings;
    private final MomentAuthors authors;
    private final ObjectMapper json = new ObjectMapper();

    public AssistantDrafts(
            ReactiveExtensionClient resources,
            CredentialStore secrets,
            AssistantSettings settings,
            MomentAuthors authors) {
        this.resources = resources;
        this.secrets = secrets;
        this.settings = settings;
        this.authors = authors;
    }

    public record Draft(
            String tool,
            Map<String, Object> input,
            String fingerprint,
            boolean approval,

            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            MomentAuthors.Author author) {
        public Draft(String tool, Map<String, Object> input, String fingerprint, boolean approval) {
            this(tool, input, fingerprint, approval, null);
        }

        public String authorLabel() {
            return author == null ? "未选择，请在设置中选择用户后重新生成预览" : author.label();
        }
    }

    public Mono<Object> stage(
            InboundCommand command, String tool, Map<String, Object> input, String fingerprint, boolean approval) {
        return stage(command, tool, input, fingerprint, approval, null);
    }

    public Mono<Object> stage(
            InboundCommand command,
            String tool,
            Map<String, Object> input,
            String fingerprint,
            boolean approval,
            Map<String, Object> before) {
        Object content = input.get("content");
        int maximum = tool.startsWith("post_") ? DraftReview.MAX_ARTICLE : 2000;
        if (content instanceof String body && (body.isBlank() || DraftReview.count(body) > maximum))
            return Mono.error(new IllegalStateException("AI_PREVIEW_TOO_LONG"));
        return settings.get()
                .flatMap(config -> {
                    Mono<Draft> proposal = MomentAuthors.publication(tool, input)
                            ? requireMomentAuthor(config)
                                    .map(author -> new Draft(tool, Map.copyOf(input), fingerprint, approval, author))
                            : Mono.just(new Draft(tool, Map.copyOf(input), fingerprint, approval));
                    return proposal.flatMap(draft ->
                            secrets.update(command.getSpec().getBodyRef(), values -> {
                                String encoded = encode(draft);
                                if (values.containsKey("proposal") && !encoded.equals(values.get("proposal")))
                                    throw new IllegalStateException("AI_PROPOSAL_ALREADY_STAGED");
                                values.put("proposal", encoded);
                                if (before != null) {
                                    try {
                                        values.put("proposalBefore", json.writeValueAsString(before));
                                    } catch (Exception error) {
                                        throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
                                    }
                                }
                                values.putIfAbsent(
                                        "proposalExpiresAt",
                                        Instant.now()
                                                .plusSeconds(config.getDraftMinutes() * 60L)
                                                .toString());
                            }));
                })
                .thenReturn(Map.of("status", "PREVIEW_ONLY", "message", "已生成预览，尚未写入。请主人引用预览确认。"));
    }

    public Mono<Draft> load(WechatBinding binding, String source) {
        if (source == null) return Mono.error(new IllegalStateException("AI_PREVIEW_REQUIRED"));
        return resources
                .fetch(InboundCommand.class, source)
                .filter(command ->
                        command.getSpec().getGeneration() == binding.getSpec().getGeneration()
                                && command.getSpec().getType() == InboundCommand.Type.AI
                                && command.getSpec().getBodyRef() != null)
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_PREVIEW_UNAVAILABLE")))
                .flatMap(command -> secrets.read(command.getSpec().getBodyRef()))
                .map(values -> {
                    if (!values.containsKey("proposal")) throw new IllegalStateException("AI_PREVIEW_REQUIRED");
                    if (values.containsKey("proposalClaim")) throw new IllegalStateException("AI_PREVIEW_ALREADY_USED");
                    if (Instant.parse(values.get("proposalExpiresAt")).isBefore(Instant.now()))
                        throw new IllegalStateException("AI_PREVIEW_EXPIRED");
                    return decode(values.get("proposal"));
                });
    }

    public Mono<MomentAuthors.Author> requireMomentAuthor(AssistantSettings.Config config) {
        return authors.resolve(config.getMomentPublishUser());
    }

    public Mono<Void> verifyAuthor(Draft draft) {
        return MomentAuthors.publication(draft.tool(), draft.input())
                ? authors.verify(draft.author()).then()
                : Mono.empty();
    }

    public Mono<Draft> claim(WechatBinding binding, String source, String claimant) {
        return load(binding, source)
                .flatMap(draft -> resources
                        .get(InboundCommand.class, source)
                        .flatMap(command -> secrets.update(command.getSpec().getBodyRef(), values -> {
                            if (values.containsKey("proposalClaim"))
                                throw new IllegalStateException("AI_PREVIEW_ALREADY_USED");
                            if (Instant.parse(values.get("proposalExpiresAt")).isBefore(Instant.now()))
                                throw new IllegalStateException("AI_PREVIEW_EXPIRED");
                            values.put("proposalClaim", claimant);
                        }))
                        .thenReturn(draft));
    }

    public Draft decode(String value) {
        try {
            return json.readValue(value, Draft.class);
        } catch (Exception e) {
            throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
        }
    }

    public String encode(Draft draft) {
        try {
            return json.writeValueAsString(draft);
        } catch (Exception e) {
            throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
        }
    }

    public Map<String, Object> input(String value) {
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
        }
    }

    public String preview(Draft draft) {
        if (draft.tool().equals("halo_cli")) {
            String parameters = HaloCliOutput.pretty(HaloCliOutput.proposal(draft.input()));
            boolean shortened = parameters.codePointCount(0, parameters.length()) > 1800;
            return "**待确认 · Halo CLI 操作**\n\n"
                    + (com.xhhao.clawbotnotify.halo.BotMomentPublisher.cliCreate(
                                    com.xhhao.clawbotnotify.assistant.cli.HaloCliCatalog.arguments(
                                            draft.input().get("arguments")))
                            ? "发布身份：" + draft.authorLabel() + "\n\n"
                            : "")
                    + MessageFormatting.truncate(parameters, 1800)
                    + (shortened ? "\n\n参数较长，此处仅摘要。确认前请在 Halo Console → 微信站点助手 → 指令记录中查看完整参数与输入正文。" : "")
                    + "\n\n尚未执行。引用本消息发送“确认执行”执行所示命令；发送“修改 你的要求”生成新预览，或“取消”。";
        }
        if (DraftReview.longArticle(draft)) {
            String content = String.valueOf(draft.input().get("content"));
            var parts = DraftReview.parts(content);
            return "**长文章待确认 · "
                    + (draft.tool().equals("post_publish_draft")
                            ? "发布草稿"
                            : draft.tool().equals("post_update_draft") ? "修改草稿" : "保存草稿")
                    + " · 版本 " + DraftReview.version(draft) + "**\n"
                    + draft.input().getOrDefault("title", "") + "\n全文 " + DraftReview.count(content) + " 字，共 "
                    + parts.size() + " 段。\n\n"
                    + "第 1/" + parts.size() + " 段：\n" + parts.getFirst()
                    + "\n\n引用发送“查看第 N 段”或“查看差异”，也可到后台指令详情查看全文。发送“修改 要求”生成新版本。\n"
                    + "确认完整内容请引用发送“" + confirmation(draft) + "”；或“取消”。";
        }
        if (com.xhhao.clawbotnotify.media.AssistantMediaTools.supports(draft.tool())) {
            String label =
                    switch (draft.tool()) {
                        case "attachment_upload" -> "上传微信图片为 Halo 附件";
                        case "post_set_cover" -> "设置文章封面";
                        default -> "发布带图瞬间（" + draft.input().getOrDefault("visible", "PUBLIC") + "）";
                    };
            var images = (java.util.List<?>) draft.input().get("images");
            return "**待确认 · " + label + "**\n版本 " + com.xhhao.clawbotnotify.assistant.review.DraftReview.version(draft)
                    + "\n" + draft.input().getOrDefault("title", "") + " "
                    + draft.input().getOrDefault("name", "")
                    + "\n图片：" + images.size() + " 张\n"
                    + (draft.tool().equals("moment_create_images") ? "发布身份：" + draft.authorLabel() + "\n" : "")
                    + images.stream()
                            .map(image -> {
                                var item = (Map<?, ?>) image;
                                return String.valueOf(item.get("filename")) + "（" + item.get("size") + " bytes）";
                            })
                            .collect(java.util.stream.Collectors.joining("\n"))
                    + "\n\n" + draft.input().getOrDefault("content", "")
                    + "\n\n确认后会上传图片" + (draft.tool().equals("attachment_upload") ? "。" : "并执行上述内容操作。")
                    + "引用发送“" + confirmation(draft) + "”执行，或“取消”。如需换图，请取消后重新选择图片。";
        }
        String label =
                switch (draft.tool()) {
                    case "comment_reply" -> "评论回复";
                    case "moment_create" -> "文字瞬间（" + draft.input().getOrDefault("visible", "PUBLIC") + "）";
                    case "post_create_draft" -> "保存文章草稿";
                    case "post_update_draft" -> "修改文章草稿";
                    case "post_publish_draft" -> "发布已有草稿";
                    default -> throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
                };
        return "**待确认 · " + label + "**\n"
                + (draft.tool().equals("moment_create") ? "发布身份：" + draft.authorLabel() + "\n" : "")
                + (draft.input().containsKey("name") ? "资源：" + draft.input().get("name") + "\n" : "")
                + (draft.input().containsKey("title") ? "标题：" + draft.input().get("title") + "\n" : "")
                + "\n" + draft.input().getOrDefault("content", "")
                + "\n\n尚未写入 Halo。引用本消息发送“" + confirmation(draft) + "”执行；"
                + "发送“修改 你的要求”生成新预览，或“取消”。预览到期后失效。"
                + (draft.approval() ? "\n这会同时审核原评论及目标楼中楼。" : "");
    }

    public static String confirmation(Draft draft) {
        if (com.xhhao.clawbotnotify.media.AssistantMediaTools.supports(draft.tool()))
            return "确认图片操作 " + DraftReview.version(draft);
        if (DraftReview.longArticle(draft))
            return (draft.tool().equals("post_publish_draft") ? "确认全文发布 " : "确认全文保存 ") + DraftReview.version(draft);
        if (draft.tool().equals("halo_cli")) return "确认执行";
        if (draft.approval()) return "确认审核并发送";
        if (draft.tool().equals("comment_reply")) return "确认发送";
        return draft.tool().startsWith("post_") && !draft.tool().equals("post_publish_draft") ? "确认保存" : "确认发布";
    }
}
