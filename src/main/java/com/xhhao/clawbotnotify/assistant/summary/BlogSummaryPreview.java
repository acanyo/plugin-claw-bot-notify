package com.xhhao.clawbotnotify.assistant.summary;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.assistant.AssistantPreferences;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.HaloCliPolicy;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Renders a summary into the ordinary immutable moment preview, using real public post identities. */
public final class BlogSummaryPreview {
    private final AssistantDrafts drafts;

    public BlogSummaryPreview(AssistantDrafts drafts) {
        this.drafts = drafts;
    }

    public Mono<Object> stage(
            InboundCommand command,
            Map<String, Object> input,
            AssistantSettings.Config config,
            URI site,
            Function<String, Mono<JsonNode>> readPost) {
        return stage(command, input, config, site, readPost, null);
    }

    public Mono<Object> stage(
            InboundCommand command,
            Map<String, Object> input,
            AssistantSettings.Config config,
            URI site,
            Function<String, Mono<JsonNode>> readPost,
            SiteReportData report) {
        if (!Set.of(
                        "posts",
                        "summary",
                        "title",
                        "period",
                        "tags",
                        "visible",
                        "template",
                        "includeLinks",
                        "start",
                        "end",
                        "wish")
                .containsAll(input.keySet())) throw invalid();
        if (!(input.get("posts") instanceof List<?> selected) || selected.isEmpty() && report == null) throw invalid();
        var names = new LinkedHashSet<String>();
        for (Object selectedName : selected) {
            if (!(selectedName instanceof String name)
                    || !name.matches("[a-z0-9][a-z0-9.-]{0,252}")
                    || !names.add(name)) throw invalid();
        }
        String summary = text(input, "summary", null, 2000);
        String title = text(input, "title", report == null ? "博客小结" : "周报", 100);
        String period = text(input, "period", "", 200);
        String tags = text(input, "tags", "#博客小结", 200);
        String visible = text(input, "visible", config.getBlogSummaryVisible(), 7);
        if (!Set.of("PUBLIC", "PRIVATE").contains(visible)) throw invalid();
        String template = text(input, "template", config.getBlogSummaryTemplate(), 2000);
        if (!AssistantPreferences.validTemplate(template)) throw invalid();
        if (SiteReportData.requested(template) && report == null) throw invalid();
        String wish = text(input, "wish", "", 160).strip();
        if (template.contains("{AI寄语}") && (wish.isBlank() || wish.contains("\n") || wish.contains("\r")))
            throw invalid();
        Object linkOption = input.getOrDefault("includeLinks", config.isBlogSummaryIncludeLinks());
        if (!(linkOption instanceof Boolean includeLinks)) throw invalid();
        return Flux.fromIterable(names)
                .flatMapSequential(
                        name -> readPost.apply(name).map(post -> article(name, post, site, includeLinks)), 4, 1)
                .collectList()
                .flatMap(articles -> {
                    var fields = Map.ofEntries(
                            Map.entry("站点统计", report == null ? "" : report.statistics(SiteReportData.current(title))),
                            Map.entry("完成事项", report == null ? "" : report.activities()),
                            Map.entry("AI寄语", wish),
                            Map.entry("本期", SiteReportData.current(title)),
                            Map.entry("下期", SiteReportData.next(title)),
                            Map.entry("标题", title),
                            Map.entry("时间范围", period),
                            Map.entry("摘要", summary),
                            Map.entry("文章列表", String.join("\n", articles)),
                            Map.entry("标签", tags));
                    var matcher = AssistantPreferences.SUMMARY_TOKEN.matcher(template);
                    var rendered = new StringBuilder();
                    while (matcher.find())
                        matcher.appendReplacement(rendered, Matcher.quoteReplacement(fields.get(matcher.group(1))));
                    matcher.appendTail(rendered);
                    String content =
                            rendered.toString().replaceAll("\n{3,}", "\n\n").strip();
                    if (content.codePointCount(0, content.length()) > 2000)
                        return Mono.error(new IllegalStateException("AI_PREVIEW_TOO_LONG"));
                    var moment = Map.<String, Object>of("content", content, "visible", visible);
                    HaloCliPolicy.invocation(
                            "moment_create", moment, command.getMetadata().getName());
                    return drafts.stage(command, "moment_create", moment, null, false);
                });
    }

    private static String article(String name, JsonNode output, URI site, boolean includeLinks) {
        var post = output.has("post") ? output.path("post") : output;
        var spec = post.path("spec");
        var metadata = post.path("metadata");
        if (!name.equals(metadata.path("name").asText())
                || !eligible(post)
                || !output.path("content").path("raw").isTextual())
            throw new IllegalStateException("AI_SUMMARY_POST_UNAVAILABLE");
        String title = spec.path("title")
                .asText()
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("([\\\\`*{}\\[\\]()#+.!_<>~-])", "\\\\$1");
        if (!includeLinks) return "• " + title;
        try {
            String permalink = post.path("status").path("permalink").asText();
            if (permalink.isBlank()) throw new IllegalArgumentException();
            URI url = site.resolve(permalink);
            if (!Set.of("http", "https").contains(url.getScheme())
                    || url.getHost() == null
                    || url.getUserInfo() != null
                    || url.getQuery() != null
                    || url.getFragment() != null) throw new IllegalArgumentException();
            return "• [" + title + "]("
                    + url.toASCIIString().replace("(", "%28").replace(")", "%29") + ")";
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("AI_SUMMARY_LINK_UNAVAILABLE");
        }
    }

    public static boolean eligible(JsonNode post) {
        var spec = post.path("spec");
        String head = spec.path("headSnapshot").asText();
        String release = spec.path("releaseSnapshot").asText();
        return spec.path("publish").asBoolean()
                && "PUBLIC".equals(spec.path("visible").asText())
                && !spec.path("deleted").asBoolean()
                && !post.path("metadata").hasNonNull("deletionTimestamp")
                && "PUBLISHED".equals(post.path("status").path("phase").asText())
                && !head.isBlank()
                && head.equals(release)
                && !spec.path("title").asText().isBlank();
    }

    private static String text(Map<String, Object> input, String key, String fallback, int max) {
        Object raw = input.getOrDefault(key, fallback);
        if (!(raw instanceof String value)
                || value.indexOf('\0') >= 0
                || value.codePointCount(0, value.length()) > max
                || (fallback == null && value.isBlank())) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI_TOOL_INPUT_INVALID");
    }
}
