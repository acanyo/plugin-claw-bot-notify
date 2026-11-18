package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantAuthority;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.assistant.summary.BlogSummaryPreview;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.BotMomentPublisher;
import com.xhhao.clawbotnotify.media.AssistantMediaTools;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.tool.ToolDefinition;
import run.halo.app.infra.ExternalUrlSupplier;

@Component
public class AssistantTools {
    private final HaloCliProcess cli;
    private final ExternalUrlSupplier externalUrl;
    private final AssistantAuthority authority;
    private final AssistantSettings settings;
    private final CredentialStore secrets;
    private final AssistantDrafts drafts;
    private final FullCliTools fullCli;
    private final AssistantMediaTools media;
    private final BotMomentPublisher moments;
    private final ObjectMapper json = new ObjectMapper();

    public AssistantTools(
            HaloCliProcess cli,
            ExternalUrlSupplier externalUrl,
            AssistantAuthority authority,
            AssistantSettings settings,
            CredentialStore secrets,
            AssistantDrafts drafts,
            AssistantMediaTools media,
            BotMomentPublisher moments) {
        this.cli = cli;
        this.externalUrl = externalUrl;
        this.authority = authority;
        this.settings = settings;
        this.secrets = secrets;
        this.drafts = drafts;
        this.media = media;
        this.moments = moments;
        this.fullCli = new FullCliTools(cli, drafts, secrets, externalUrl);
    }

    public List<ToolDefinition> definitions(WechatBinding binding, InboundCommand command, boolean writes) {
        var tools = new ArrayList<ToolDefinition>();
        if (command.getSpec().getCommentName() != null) return tools;
        tools.add(tool(
                binding,
                command,
                "halo_cli_help",
                "读取已安装 Halo CLI 的真实帮助。path 为空列出所有命令；可直接提供完整路径如 [comment,list]、[shop,product,update]。覆盖 auth、post、single-page、search、plugin、theme、attachment、backup、moment、comment（含 reply）、notification、shop、completion 及未来新增命令。",
                Map.of("path", Map.of("type", "array", "items", Map.of("type", "string"))),
                List.of()));
        tools.add(tool(
                binding,
                command,
                "halo_cli",
                "执行完整 Halo CLI 能力。已知命令可直接调用，服务端自动按真实帮助校验；不清楚参数或收到 CLI_OPTION_INVALID 时再查 halo_cli_help，避免重复查询帮助。arguments 为逐项参数数组，不含 halo。查询直接返回完整脱敏结果（含总数和时间）；写操作先预览，主人确认执行后才执行。支持 stdin，profile 固定本站。多项独立查询优先 halo_cli_batch。",
                Map.of(
                        "arguments",
                        Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1),
                        "stdin",
                        Map.of("type", "string")),
                List.of("arguments")));
        tools.add(tool(
                binding,
                command,
                "halo_cli_batch",
                "一次并行执行多项独立只读 CLI 查询，避免多轮模型往返。周报/跨模块统计优先一次读取 commands=[[post,list,--page=1,--size=100],[moment,list,--page=1,--size=100],[comment,list,--page=1,--size=100],[comment,reply,list,--page=1,--size=100]]。这些路径和 page/size 参数为已知常用命令，直接调用即可；服务端仍按已安装 CLI 帮助校验。返回按输入顺序排列的完整脱敏结果（含时间、总数），有下一页再批量取下一页。拒绝整个批次中的任何写操作或本地文件输出；某查询失败时逐项报告，不重试成功项。",
                Map.of(
                        "commands",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "items",
                                Map.of("type", "array", "minItems", 1, "items", Map.of("type", "string")))),
                List.of("commands")));
        tools.add(tool(
                binding,
                command,
                "wechat_media_list",
                "列出当前消息附带或最近收到的微信图片标识、类型和有效期。这里只读取加密暂存信息，不上传或发布。",
                Map.of(),
                List.of()));
        if (writes) {
            var ids = Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1, "maxItems", 9);
            tools.add(tool(
                    binding,
                    command,
                    "attachment_upload",
                    "将指定微信图片上传为 Halo 附件，先生成预览，确认后上传。先调用 wechat_media_list 获取 mediaIds。",
                    Map.of("mediaIds", ids),
                    List.of("mediaIds")));
            tools.add(tool(
                    binding,
                    command,
                    "moment_create_images",
                    "上传微信图片并以设置中选择的用户发布带图瞬间的待确认预览，确认后上传及发布。可公开或私密，最多9图。",
                    Map.of(
                            "mediaIds",
                            ids,
                            "content",
                            Map.of("type", "string"),
                            "visible",
                            Map.of("type", "string", "enum", List.of("PUBLIC", "PRIVATE"))),
                    List.of("mediaIds", "content")));
            tools.add(tool(
                    binding,
                    command,
                    "post_set_cover",
                    "把指定微信图片上传为附件并设为现有文章封面的预览，不修改文章正文；确认前检查目标没有变化。",
                    Map.of("mediaIds", ids, "name", Map.of("type", "string")),
                    List.of("mediaIds", "name")));
        }
        for (String kind : List.of("post", "moment")) {
            tools.add(tool(
                    binding,
                    command,
                    kind + "_list",
                    "查询本站" + (kind.equals("post") ? "文章（含草稿）" : "瞬间") + "列表",
                    Map.of(
                            "page",
                            Map.of("type", "integer", "minimum", 1, "maximum", 100),
                            "size",
                            Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                    List.of()));
            tools.add(tool(
                    binding,
                    command,
                    kind + "_get",
                    "按资源 name 读取本站内容",
                    Map.of("name", Map.of("type", "string")),
                    List.of("name")));
        }
        if (writes) {
            tools.add(tool(
                    binding,
                    command,
                    "blog_summary_preview",
                    "生成博客摘要或站点日报/周报/月报瞬间预览，尚不发布。posts为实际公开文章name，报告可为空；summary为忠实摘要；wish为AI原创的一句简短寄语。报告必须提供start/end（含时区ISO时间、起含止不含），服务端查询完整分页填入真实{站点统计}/{完成事项}。模板支持{标题}/{时间范围}/{摘要}/{文章列表}/{标签}/{AI寄语}/{本期}/{下期}。超过2000字请精简。",
                    Map.ofEntries(
                            Map.entry("start", Map.of("type", "string", "description", "统计起点，含时区的ISO时间，包含")),
                            Map.entry("end", Map.of("type", "string", "description", "统计终点，含时区的ISO时间，不包含")),
                            Map.entry("wish", Map.of("type", "string", "description", "原创中文寄语，一句话，不超过160字")),
                            Map.entry(
                                    "posts",
                                    Map.of(
                                            "type",
                                            "array",
                                            "items",
                                            Map.of("type", "string"),
                                            "minItems",
                                            0,
                                            "uniqueItems",
                                            true)),
                            Map.entry("summary", Map.of("type", "string")),
                            Map.entry("title", Map.of("type", "string")),
                            Map.entry("period", Map.of("type", "string")),
                            Map.entry("tags", Map.of("type", "string")),
                            Map.entry("visible", Map.of("type", "string", "enum", List.of("PUBLIC", "PRIVATE"))),
                            Map.entry("template", Map.of("type", "string")),
                            Map.entry("includeLinks", Map.of("type", "boolean"))),
                    List.of("posts", "summary")));
            tools.add(tool(
                    binding,
                    command,
                    "moment_create",
                    "生成文字瞬间的待确认预览，确认后以设置中选择的用户发布；默认公开，正文最多2000字；不会立即发布",
                    Map.of(
                            "content",
                            Map.of("type", "string"),
                            "visible",
                            Map.of("type", "string", "enum", List.of("PUBLIC", "PRIVATE"))),
                    List.of("content")));
            tools.add(tool(
                    binding,
                    command,
                    "post_create_draft",
                    "生成保存 Markdown 草稿的待确认预览，正文最多30000字，支持分段查看和全文确认；不会立即保存",
                    Map.of("title", Map.of("type", "string"), "content", Map.of("type", "string")),
                    List.of("title", "content")));
        }
        if (writes) {
            tools.add(tool(
                    binding,
                    command,
                    "post_update_draft",
                    "修改未发布文章草稿的预览，先读取原文，正文最多30000字，支持修改差异和全文确认",
                    Map.of(
                            "name",
                            Map.of("type", "string"),
                            "title",
                            Map.of("type", "string"),
                            "content",
                            Map.of("type", "string")),
                    List.of("name", "title", "content")));
            tools.add(tool(
                    binding,
                    command,
                    "post_publish_draft",
                    "预览并请求主人确认发布已有草稿；不能修改已发布文章",
                    Map.of("name", Map.of("type", "string")),
                    List.of("name")));
        }
        return tools;
    }

    private ToolDefinition tool(
            WechatBinding binding,
            InboundCommand command,
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        required,
                        "additionalProperties",
                        false))
                .executor(context -> execute(binding, command, name, context.getInput()))
                .build();
    }

    public Mono<Object> execute(WechatBinding binding, InboundCommand command, String tool, Map<String, Object> input) {
        return Mono.defer(() -> {
                    if (tool.equals("blog_summary_preview"))
                        return authority.check(binding).then(settings.get()).flatMap(config -> {
                            if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                            if (!config.isAllowWrites())
                                return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                            return preflight(command).then(Mono.defer(() -> {
                                try {
                                    String template = input.getOrDefault("template", config.getBlogSummaryTemplate())
                                            .toString();
                                    if (com.xhhao.clawbotnotify.assistant.summary.SiteReportData.requested(template)) {
                                        var start = java.time.Instant.parse(String.valueOf(input.get("start")));
                                        var end = java.time.Instant.parse(String.valueOf(input.get("end")));
                                        if (!start.isBefore(end)
                                                || end.isAfter(
                                                        java.time.Instant.now().plusSeconds(300)))
                                            return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
                                        var window =
                                                new com.xhhao.clawbotnotify.assistant.summary.BlogSummarySchedule
                                                        .Window(
                                                        start,
                                                        end,
                                                        start.atZone(
                                                                        com.xhhao.clawbotnotify.assistant.summary
                                                                                .BlogSummarySchedule.ZONE)
                                                                + " 至 "
                                                                + end.atZone(
                                                                        com.xhhao.clawbotnotify.assistant.summary
                                                                                .BlogSummarySchedule.ZONE),
                                                        input.getOrDefault("title", "周报")
                                                                .toString());
                                        var normalized = new java.util.LinkedHashMap<>(input);
                                        normalized.put("period", window.period());
                                        return reportSources(command, window)
                                                .flatMap(report -> reportPreview(binding, command, normalized, report));
                                    }
                                    return new BlogSummaryPreview(drafts)
                                            .stage(
                                                    command,
                                                    input,
                                                    config,
                                                    externalUrl.getRaw().toURI(),
                                                    name -> runCli(command, List.of("post", "get", name)));
                                } catch (java.net.URISyntaxException | java.time.format.DateTimeParseException error) {
                                    return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
                                }
                            }));
                        });
                    if (AssistantMediaTools.supports(tool))
                        return authority.check(binding).then(settings.get()).flatMap(config -> {
                            if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                            return media.execute(binding, command, tool, input, config.isAllowWrites());
                        });
                    if (tool.equals("halo_cli") || tool.equals("halo_cli_help") || tool.equals("halo_cli_batch"))
                        return authority.check(binding).then(settings.get()).flatMap(config -> {
                            if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                            if (tool.equals("halo_cli_batch"))
                                return fullCli.readBatch(command, input, () -> preflight(command));
                            return tool.equals("halo_cli_help")
                                    ? fullCli.help(command, input)
                                    : fullCli.execute(command, input, config.isAllowWrites(), () -> preflight(command));
                        });
                    var invocation = HaloCliPolicy.invocation(
                            tool, input, command.getMetadata().getName());
                    return authority.check(binding).then(settings.get()).flatMap(config -> {
                        if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                        if (invocation.write() && !config.isAllowWrites())
                            return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                        return preflight(command)
                                .then(Mono.defer(() -> invocation.write()
                                        ? stage(command, invocation, input)
                                        : timedRun(command, invocation)));
                    });
                })
                .contextWrite(context -> context.put(ManagedCliCredentials.BINDING_CONTEXT, binding))
                .onErrorMap(error -> new IllegalStateException(safeCode(error)));
    }

    public Mono<Object> reviseCli(InboundCommand command, Map<String, Object> input) {
        return fullCli.stageRevision(command, input);
    }

    public Mono<List<com.xhhao.clawbotnotify.assistant.summary.BlogSummarySources.Article>> summarySources(
            InboundCommand command, com.xhhao.clawbotnotify.assistant.summary.BlogSummarySchedule.Window window) {
        return preflight(command)
                .then(Mono.defer(() -> new com.xhhao.clawbotnotify.assistant.summary.BlogSummarySources()
                        .read(window, arguments -> runCli(command, arguments))));
    }

    public Mono<com.xhhao.clawbotnotify.assistant.summary.SiteReportData> reportSources(
            InboundCommand command, com.xhhao.clawbotnotify.assistant.summary.BlogSummarySchedule.Window window) {
        return preflight(command)
                .then(Mono.defer(() -> com.xhhao.clawbotnotify.assistant.summary.SiteReportData.read(
                        window, args -> runCli(command, args))));
    }

    public Mono<Object> reportPreview(
            WechatBinding binding,
            InboundCommand command,
            Map<String, Object> input,
            com.xhhao.clawbotnotify.assistant.summary.SiteReportData report) {
        return authority.check(binding).then(settings.get()).flatMap(config -> {
            if (!config.isEnabled() || !config.isAllowWrites())
                return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
            try {
                return new BlogSummaryPreview(drafts)
                        .stage(
                                command,
                                input,
                                config,
                                externalUrl.getRaw().toURI(),
                                name -> runCli(command, List.of("post", "get", name)),
                                report);
            } catch (java.net.URISyntaxException error) {
                return Mono.error(new IllegalStateException("CLI_SITE_MISMATCH"));
            }
        });
    }

    private Mono<Object> stage(InboundCommand command, HaloCliPolicy.Invocation invocation, Map<String, Object> input) {
        if (!invocation.tool().equals("post_update_draft") && !invocation.tool().equals("post_publish_draft"))
            return drafts.stage(command, invocation.tool(), input, null, false);
        return postSnapshot(command, invocation.name()).flatMap(snapshot -> {
            var values = new java.util.LinkedHashMap<>(input);
            if (invocation.tool().equals("post_publish_draft")) {
                Map<?, ?> resource = (Map<?, ?>) HaloCliPolicy.result(
                        HaloCliPolicy.invocation("post_get", Map.of("name", invocation.name()), "read"), snapshot);
                values.put("title", resource.get("title"));
                values.put("content", resource.get("content"));
                if (String.valueOf(values.get("content")).isBlank())
                    return Mono.error(new IllegalStateException("AI_DRAFT_CONTENT_UNAVAILABLE"));
            }
            Map<?, ?> previous = (Map<?, ?>) HaloCliPolicy.result(
                    HaloCliPolicy.invocation("post_get", Map.of("name", invocation.name()), "read"), snapshot);
            return drafts.stage(
                    command,
                    invocation.tool(),
                    values,
                    StableIds.hash(snapshot.toString()),
                    false,
                    Map.of("title", previous.get("title"), "content", previous.get("content")));
        });
    }

    private Mono<com.fasterxml.jackson.databind.JsonNode> postSnapshot(InboundCommand command, String name) {
        return runCli(command, List.of("post", "get", name)).map(snapshot -> {
            var root = snapshot.has("post") ? snapshot.path("post") : snapshot;
            if (!root.path("spec").path("publish").isBoolean()
                    || root.path("spec").path("publish").asBoolean()
                    || root.path("spec").path("deleted").asBoolean()
                    || !root.path("metadata").path("deletionTimestamp").isMissingNode()
                            && !root.path("metadata").path("deletionTimestamp").isNull()
                    || !name.equals(root.path("metadata").path("name").asText()))
                throw new IllegalStateException("AI_POST_NOT_DRAFT");
            if (!snapshot.path("content").path("raw").isTextual())
                throw new IllegalStateException("AI_DRAFT_CONTENT_UNAVAILABLE");
            if (snapshot.path("content")
                            .path("raw")
                            .asText()
                            .codePointCount(
                                    0,
                                    snapshot.path("content")
                                            .path("raw")
                                            .asText()
                                            .length())
                    > 30000) throw new IllegalStateException("AI_PREVIEW_TOO_LONG");
            return snapshot;
        });
    }

    public Mono<Object> executeConfirmed(WechatBinding binding, InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef())
                .flatMap(data -> {
                    if (!data.containsKey("confirmedProposal"))
                        return Mono.error(new IllegalStateException("AI_PREVIEW_REQUIRED"));
                    var draft = drafts.decode(data.get("confirmedProposal"));
                    if (AssistantMediaTools.supports(draft.tool()))
                        return authority.check(binding).then(settings.get()).flatMap(config -> {
                            if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                            if (!config.isAllowWrites())
                                return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                            return media.confirmed(binding, command, draft);
                        });
                    if (draft.tool().equals("halo_cli"))
                        return authority.check(binding).then(settings.get()).flatMap(config -> {
                            if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                            if (!config.isAllowWrites())
                                return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                            return fullCli.confirmed(command, draft, () -> preflight(command), invocation -> {
                                if (!BotMomentPublisher.cliCreate(invocation.arguments())) return Mono.empty();
                                return token(binding)
                                        .flatMap(token -> moments.publishCli(
                                                token,
                                                command.getMetadata().getName(),
                                                invocation.arguments(),
                                                draft.author()))
                                        .map(result -> (Object) Map.of(
                                                "success",
                                                true,
                                                "exitCode",
                                                0,
                                                "output",
                                                HaloCliOutput.sanitize(result),
                                                "stderr",
                                                ""));
                            });
                        });
                    var invocation = HaloCliPolicy.invocation(
                            draft.tool(), draft.input(), command.getMetadata().getName());
                    return authority.check(binding).then(settings.get()).flatMap(config -> {
                        if (!config.isEnabled()) return Mono.error(new IllegalStateException("AI_DISABLED"));
                        if (!config.isAllowWrites()) return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
                        return preflight(command).then(Mono.defer(() -> {
                            Mono<Void> unchanged = draft.fingerprint() == null
                                    ? Mono.empty()
                                    : postSnapshot(command, invocation.name())
                                            .flatMap(snapshot -> draft.fingerprint()
                                                            .equals(StableIds.hash(snapshot.toString()))
                                                    ? Mono.empty()
                                                    : Mono.error(new IllegalStateException("AI_DRAFT_CHANGED")));
                            return unchanged.then(
                                    Mono.defer(() -> write(binding, command, invocation, draft.author())));
                        }));
                    });
                })
                .contextWrite(context -> context.put(ManagedCliCredentials.BINDING_CONTEXT, binding))
                .onErrorMap(error -> new IllegalStateException(safeCode(error)));
    }

    private Mono<Object> timedRun(InboundCommand command, HaloCliPolicy.Invocation invocation) {
        return runCli(command, invocation.arguments()).map(output -> HaloCliPolicy.result(invocation, output));
    }

    private Mono<com.fasterxml.jackson.databind.JsonNode> runCli(InboundCommand command, List<String> arguments) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return cli.run(arguments).doOnEach(signal -> {
                if (command != null && (signal.isOnNext() || signal.isOnError()))
                    command.getSpec()
                            .setCliDurationMs((command.getSpec().getCliDurationMs() == null
                                            ? 0
                                            : command.getSpec().getCliDurationMs())
                                    + (System.nanoTime() - start) / 1_000_000);
            });
        });
    }

    public Mono<Boolean> available() {
        return preflight(null)
                .then(cli.run(List.of("post", "list", "--page=1", "--size=1")))
                .map(result -> true);
    }

    private Mono<Void> preflight(InboundCommand command) {
        return runCli(command, List.of("auth", "current")).flatMap(profile -> {
            try {
                URI actual = URI.create(profile.path("baseUrl").asText()).normalize();
                URI expected = externalUrl.getRaw().toURI().normalize();
                if (!canonical(actual).equals(canonical(expected))) throw new IllegalStateException();
                return Mono.empty();
            } catch (Exception error) {
                return Mono.error(new IllegalStateException("CLI_SITE_MISMATCH"));
            }
        });
    }

    private static String canonical(URI uri) {
        if (!List.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) throw new IllegalArgumentException();
        return uri.toString().replaceAll("/+$", "");
    }

    private Mono<String> token(WechatBinding binding) {
        return secrets.read(binding.getSpec().getCredentialRef()).map(data -> {
            String token = data.get("haloPat");
            if (token == null || token.isBlank()) throw new IllegalStateException("CREDENTIAL_UNAVAILABLE");
            return token;
        });
    }

    private Mono<Object> write(
            WechatBinding binding,
            InboundCommand command,
            HaloCliPolicy.Invocation invocation,
            com.xhhao.clawbotnotify.halo.MomentAuthors.Author author) {
        String fingerprint = StableIds.hash(invocation.arguments().toArray(String[]::new));
        String ref = command.getSpec().getBodyRef();
        // The encrypted Secret update uses optimistic locking. A second claim cannot dispatch a process.
        return secrets.read(ref).flatMap(values -> {
            if (values.containsKey("mutation")) {
                if (fingerprint.equals(values.get("mutation")) && values.containsKey("mutationResult")) {
                    try {
                        return Mono.just(json.readValue(values.get("mutationResult"), Object.class));
                    } catch (Exception error) {
                        return Mono.error(new IllegalStateException("CLI_RESULT_UNKNOWN"));
                    }
                }
                return Mono.error(new IllegalStateException("AI_WRITE_ALREADY_CLAIMED"));
            }
            return secrets.update(ref, data -> {
                        if (data.containsKey("mutation")) throw new IllegalStateException("AI_WRITE_ALREADY_CLAIMED");
                        data.put("mutation", fingerprint);
                        data.put("mutationName", invocation.name());
                        data.put("mutationTool", invocation.tool());
                        if (invocation.tool().equals("post_create_draft")) {
                            data.put("mutationLookup", "slug");
                            data.put(
                                    "mutationTitle",
                                    invocation.arguments().stream()
                                            .filter(arg -> arg.startsWith("--title="))
                                            .findFirst()
                                            .orElse("")
                                            .substring(8));
                        }
                    })
                    .then(Mono.defer(() -> invocation.tool().equals("moment_create")
                            ? token(binding)
                                    .flatMap(token -> moments.publishCli(
                                            token, command.getMetadata().getName(), invocation.arguments(), author))
                                    .map(result -> HaloCliPolicy.result(invocation, result))
                            : timedRun(command, invocation)))
                    .flatMap(result -> secrets.update(ref, data -> {
                                try {
                                    data.put("mutationResult", json.writeValueAsString(result));
                                    if (result instanceof Map<?, ?> resource
                                            && resource.get("name") instanceof String name) {
                                        data.put("mutationName", name);
                                        data.remove("mutationLookup");
                                    }
                                } catch (Exception error) {
                                    throw new IllegalStateException("CLI_RESULT_UNKNOWN");
                                }
                            })
                            .thenReturn(result));
        });
    }

    public static String safeCode(Throwable error) {
        String code = error.getMessage();
        return code != null && code.matches("(?:AI_|CLI_|BINDING_|HALO_|CREDENTIAL_|MEDIA_)[A-Z_]{1,64}")
                ? code
                : "AI_REQUEST_FAILED";
    }
}
