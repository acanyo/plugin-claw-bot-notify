package com.xhhao.clawbotnotify.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.comment.CommentAuthors;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.halo.CommentTargetReader;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class MessageComposer {
    public static final String HELP =
            "**Halo ClawBot**\n\n收到评论或楼中楼通知后：\n• 引用通知，直接输入正文 → 发布回复\n• 引用通知，发送“查看” → 评论详情\n• 引用通知，发送“忽略”或“标记已处理” → 移出插件待处理\n• 待审核时先保存正文；引用确认提示，发送“审核并回复” → 审核并发布\n\n找回评论：\n开始 / 待处理 / 待处理 2\n查看 C-编号（编号仅用于列表定位）\n\nAI 文章最多30,000字，可引用预览发送“查看第 N 段”“查看差异”，按版本号确认全文。\n发送图片后，引用回执可上传附件、发布带图瞬间或设置文章封面；先预览确认。支持PNG/JPEG/GIF，每张10MiB，最多9张，暂存24小时。\n\n评论回复正文支持 Markdown；原始 HTML 会转义。最长 2,000 字，写指令 24 小时内有效。需要 PAT 的“评论管理”权限。回复遵循 Halo 后台语义；并发审核状态变更以 Halo 为准。";
    private final ReactiveExtensionClient resources;
    private final CommentLinkRepository links;
    private final HaloCommentReader comments;
    private final CommentTargetReader targets;
    private final CommentAuthors authors;
    private final CredentialStore secrets;

    public MessageComposer(
            ReactiveExtensionClient resources,
            CommentLinkRepository links,
            HaloCommentReader comments,
            CommentAuthors authors,
            CredentialStore secrets) {
        this.resources = resources;
        this.links = links;
        this.comments = comments;
        this.targets = new CommentTargetReader(comments);
        this.authors = authors;
        this.secrets = secrets;
    }

    public Mono<String> compose(WechatBinding binding, OutboundTask task, Map<String, String> credentials) {
        String token = credentials.get("haloPat");
        String kind = task.getSpec().getKind();
        if (kind == null || kind.equals("TEST")) return Mono.just(OutboundDeliveryWorker.TEST_TEXT);
        if (kind.equals("AI_PROGRESS")) return Mono.just(com.xhhao.clawbotnotify.assistant.AssistantProgress.TEXT);
        if (kind.equals("COMMENT") || kind.equals("REPLY"))
            return links.forTarget(
                            binding.getSpec().getGeneration(),
                            task.getSpec().getCommentName(),
                            task.getSpec().getTargetReplyName())
                    .flatMap(link -> detail(binding, token, link, false));
        if (!kind.equals("COMMAND")) return Mono.error(new IllegalStateException("OUTBOUND_KIND_INVALID"));
        return resources
                .get(InboundCommand.class, task.getSpec().getCommandName())
                .filter(command ->
                        command.getSpec().getGeneration() == binding.getSpec().getGeneration())
                .flatMap(command -> switch (command.getSpec().getType()) {
                    case HELP ->
                        Mono.just(
                                HELP
                                        + "\n\n启用 AI 助手后，不引用评论直接发需求即可对话，例如“最近有哪些文章”“发一条瞬间：今天上线新功能”。引用评论发送“AI 回复”生成建议；引用预览发送“修改 要求”“确认发布 / 确认保存”或“取消”。发送“新对话”或 /clear 清空上下文。配置入口：插件设置 → 智能助手。");
                    case AI, AI_RESET, AI_PREVIEW, AI_MEDIA, AI_CONFIRM, AI_APPROVE, AI_CANCEL ->
                        secrets.read(command.getSpec().getBodyRef())
                                .map(data -> data.getOrDefault(
                                        "aiResponse",
                                        "AI 指令未完成（"
                                                + java.util.Objects.toString(
                                                        command.getSpec().getErrorCode(), "结果待核查")
                                                + "）。请先核查指令记录及站点结果。"));
                    case INVALID -> Mono.just("指令格式不正确，请发送“帮助”查看用法。");
                    case UNSUPPORTED -> Mono.just("请引用评论通知或评论详情，再直接输入回复正文。可先发送“待处理”，按编号查看后再引用详情。");
                    case QUOTE_UNRESOLVED -> Mono.just("无法准确定位这条引用对应的评论，未发布任何回复。请发“待处理”，按编号查看后引用新的评论详情；引用列表无法选择某一条评论。");
                    case REPLY, APPROVE_REPLY -> Mono.just(writeResult(command));
                    case IGNORE, MARK_HANDLED -> Mono.just(actionResult(command));
                    case START, PENDING ->
                        pending(binding, token, command.getSpec().getPage())
                                .map(text -> command.getSpec().getType() == InboundCommand.Type.START
                                        ? "Halo ClawBot 已连接。\n" + text + "\n发送“帮助”查看用法。"
                                        : text);
                    case VIEW ->
                        (command.getSpec().getCommentName() == null
                                        ? links.forCode(
                                                binding.getSpec().getGeneration(),
                                                command.getSpec().getShortCode())
                                        : links.forTarget(
                                                binding.getSpec().getGeneration(),
                                                command.getSpec().getCommentName(),
                                                command.getSpec().getTargetReplyName()))
                                .flatMap(link -> {
                                    task.getSpec().setCommentName(link.getSpec().getCommentName());
                                    task.getSpec()
                                            .setTargetReplyName(link.getSpec().getTargetReplyName());
                                    task.getSpec().setShortCode(link.getSpec().getShortCode());
                                    return detail(binding, token, link, true);
                                })
                                .defaultIfEmpty("当前绑定没有这个评论编号，请从待处理列表选择。");
                });
    }

    private Mono<String> detail(WechatBinding binding, String token, CommentLink link, boolean full) {
        if (link.getSpec().getState() == CommentLink.State.DELETED)
            return full ? Mono.just(link.getSpec().getShortCode() + "：评论已删除。") : Mono.empty();
        return targets.get(
                        binding,
                        token,
                        link.getSpec().getCommentName(),
                        link.getSpec().getTargetReplyName())
                .switchIfEmpty(Mono.defer(() -> links.deleted(link).then(Mono.empty())))
                .flatMap(target -> {
                    Mono<Boolean> suppressed = !full && target.reply() != null
                            ? authors.moderator(target.content().getOwner())
                                    .map(moderator -> moderator
                                            || target.content().getOwner() != null
                                                    && "User"
                                                            .equals(target.content()
                                                                    .getOwner()
                                                                    .getKind())
                                                    && java.util.Objects.equals(
                                                            binding.getSpec().getHaloUsername(),
                                                            target.content()
                                                                    .getOwner()
                                                                    .getName()))
                            : Mono.just(false);
                    return suppressed.flatMap(skip -> skip
                            ? Mono.empty()
                            : Mono.zip(
                                            siteTitle(),
                                            comments.title(target.comment(), token),
                                            context(binding, token, target))
                                    .map(labels -> "**"
                                            + (target.reply() == null
                                                    ? full ? "评论详情" : "新评论"
                                                    : full ? "楼中楼详情" : "新楼中楼回复")
                                            + "** · " + plain(labels.getT1(), 60)
                                            + "\n\n**" + plain(labels.getT2(), 120) + "**"
                                            + "\n编号：" + link.getSpec().getShortCode()
                                            + "\n评论者：" + author(target.content())
                                            + "\n状态：" + (target.approved() ? "已审核" : "待审核")
                                            + (Boolean.TRUE.equals(target.content()
                                                                    .getHidden())
                                                            || Boolean.TRUE.equals(target.comment()
                                                                    .getSpec()
                                                                    .getHidden())
                                                    ? " · 已隐藏"
                                                    : "")
                                            + "\n\n"
                                            + MessageFormatting.commentMarkdown(
                                                    target.content().getContent(),
                                                    target.content().getRaw(),
                                                    full ? 3000 : 300)
                                            + labels.getT3()
                                            + "\n\n引用本消息直接输入正文即可回复" + (target.reply() == null ? "" : "这条楼中楼")
                                            + "；引用后发“查看”获取详情。"));
                })
                .switchIfEmpty(full ? Mono.just(link.getSpec().getShortCode() + "：原评论或楼中楼已删除。") : Mono.empty());
    }

    private Mono<String> context(WechatBinding binding, String token, CommentTargetReader.Target target) {
        if (target.reply() == null) return Mono.just("");
        String root = "\n\n**原评论 · " + author(target.comment().getSpec()) + "**\n"
                + MessageFormatting.commentMarkdown(
                        target.comment().getSpec().getContent(),
                        target.comment().getSpec().getRaw(),
                        180);
        String quoted = target.reply().getSpec().getQuoteReply();
        if (quoted == null || quoted.isBlank()) return Mono.just(root);
        return comments.reply(binding, token, target.comment().getMetadata().getName(), quoted)
                .map(reply -> root + "\n\n**所回复的内容 · " + author(reply.getSpec()) + "**\n"
                        + MessageFormatting.commentMarkdown(
                                reply.getSpec().getContent(), reply.getSpec().getRaw(), 180))
                .defaultIfEmpty(root + "\n\n所回复的内容已删除。")
                .onErrorReturn(root + "\n\n暂时无法读取所回复的内容。");
    }

    private Mono<String> pending(WechatBinding binding, String token, int pageNumber) {
        return comments.probe(binding, token)
                .then(links.page(binding.getSpec().getGeneration(), pageNumber, 10, true))
                .flatMap(page -> Flux.fromIterable(page.getItems())
                        .concatMap(link -> targets.get(
                                        binding,
                                        token,
                                        link.getSpec().getCommentName(),
                                        link.getSpec().getTargetReplyName())
                                .map(comment -> link.getSpec().getShortCode() + " · "
                                        + (comment.reply() == null ? "" : "楼中楼 · ") + author(comment.content()) + "\n"
                                        + plain(comment.content().getRaw(), 80))
                                .switchIfEmpty(
                                        Mono.defer(() -> links.deleted(link).then(Mono.empty()))))
                        .collectList()
                        .map(items -> "插件待处理 · 第 " + pageNumber + " 页（" + page.getTotal() + " 条记录）\n"
                                + (items.isEmpty() ? "本页暂无待处理评论。" : String.join("\n\n", items))
                                + (page.hasNext() ? "\n下一页：待处理 " + (pageNumber + 1) : "")
                                + "\n可发送“查看 C-编号”，再引用详情回复、忽略或标记已处理。"));
    }

    private static String actionResult(InboundCommand command) {
        if (command.getSpec().getWriteState() == InboundCommand.WriteState.SUCCEEDED)
            return command.getSpec().getType() == InboundCommand.Type.IGNORE
                    ? "已忽略这条评论，不再列入插件待处理。网站评论保持原样。"
                    : "已标记为已处理，不再列入插件待处理。网站评论保持原样。";
        return "未能更新处理状态。请检查评论是否仍存在、PAT 权限及绑定状态后重新操作。";
    }

    private static String writeResult(InboundCommand command) {
        var spec = command.getSpec();
        if (spec.getWriteState() == null) return "回复尚未处理。";
        return switch (spec.getWriteState()) {
            case SUCCEEDED -> "**回复已发布**\n内容已保存到 Halo。\n引用本消息可继续回复这条评论。";
            case AWAITING_APPROVAL ->
                "**原评论或楼中楼待审核，回复尚未发布**\n正文已安全保存。请引用本消息发送“审核并回复”，确认审核目标评论及楼中楼，并发布刚才的正文。\n也可继续在 Halo 后台处理。";
            case UNKNOWN, EXECUTING, PARTIAL ->
                "**回复结果需要核查**\n"
                        + (spec.getWriteState() == InboundCommand.WriteState.PARTIAL
                                ? "原评论现已审核，但未能确认回复是否创建。"
                                : "请求可能已写入网站，暂时无法确认。")
                        + "\n不会自动重复发布，请先在 Halo 后台核查。";
            case REJECTED ->
                switch (spec.getErrorCode() == null ? "" : spec.getErrorCode()) {
                    case "REPLY_BODY_INVALID" -> "回复正文不能为空且最多 2,000 字，未发布回复。";
                    case "COMMAND_EXPIRED" -> "这条写指令已过期，未发布回复。请重新引用评论后输入正文。";
                    case "DRAFT_ALREADY_CONFIRMED" -> "这份待审核回复已确认过，未重复发布。请先核查上一次结果。";
                    case "COMMENT_DELETED" -> "原评论已删除或不在当前绑定范围内，未发布回复。";
                    case "HALO_WRITE_CONTENT_REJECTED" -> "站点拒绝了回复正文的格式或内容，未发布回复。请调整正文后重新引用发送。";
                    case "HALO_WRITE_FORBIDDEN",
                            "HALO_READ_FORBIDDEN",
                            "HALO_ADMIN_REQUIRED",
                            "HALO_IDENTITY_MISMATCH" -> "回复未发布。请检查 PAT 的“评论管理”权限及绑定账号身份，再发起新的回复。";
                    default -> "站点拒绝了回复，未发布。请检查账号状态和站点连接，再发起新的回复。";
                };
            case READY -> "回复正在等待处理。";
        };
    }

    private Mono<String> siteTitle() {
        return resources
                .fetch(ConfigMap.class, "system")
                .map(config -> {
                    try {
                        return new ObjectMapper()
                                .readTree(config.getData().getOrDefault("basic", "{}"))
                                .path("title")
                                .asText("Halo");
                    } catch (Exception e) {
                        return "Halo";
                    }
                })
                .defaultIfEmpty("Halo");
    }

    private static String author(Comment.BaseCommentSpec content) {
        var owner = content.getOwner();
        return owner == null
                        || owner.getDisplayName() == null
                        || owner.getDisplayName().isBlank()
                ? "访客"
                : plain(owner.getDisplayName(), 60);
    }

    static String plain(String text, int limit) {
        if (text == null) return "";
        String safe = HtmlUtils.htmlUnescape(text.replaceAll("<[^>]{0,2000}>", ""))
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "")
                .strip();
        int count = safe.codePointCount(0, safe.length());
        return count <= limit ? safe : safe.substring(0, safe.offsetByCodePoints(0, limit)) + "…";
    }
}
