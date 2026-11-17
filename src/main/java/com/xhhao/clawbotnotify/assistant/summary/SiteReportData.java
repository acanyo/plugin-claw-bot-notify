package com.xhhao.clawbotnotify.assistant.summary;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Counts public data on the server and derives verifiable activities; the model only writes prose. */
public record SiteReportData(BlogSummarySources.Snapshot posts, Count moments, Count comments, Count replies) {
    public record Count(Long total, Long added) {
        static Count unavailable() {
            return new Count(null, null);
        }

        boolean available() {
            return total != null && added != null;
        }
    }

    public static Mono<SiteReportData> read(
            BlogSummarySchedule.Window window, Function<List<String>, Mono<JsonNode>> run) {
        return Mono.zip(
                        new BlogSummarySources().snapshot(window, run),
                        count(List.of("moment", "list"), "moment", "releaseTime", window, run),
                        count(List.of("comment", "list"), "comment", "creationTime", window, run),
                        count(List.of("comment", "reply", "list"), "reply", "creationTime", window, run))
                .map(values -> new SiteReportData(values.getT1(), values.getT2(), values.getT3(), values.getT4()));
    }

    private static Mono<Count> count(
            List<String> path,
            String kind,
            String timeField,
            BlogSummarySchedule.Window window,
            Function<List<String>, Mono<JsonNode>> run) {
        return ReportPages.read(path, run)
                .map(row -> row.has(kind) ? row.path(kind) : row)
                .filter(row -> !row.path("metadata").hasNonNull("deletionTimestamp")
                        && !row.path("spec").path("deleted").asBoolean()
                        && row.path("spec").path("approved").asBoolean()
                        && (kind.equals("moment")
                                ? "PUBLIC"
                                        .equals(row.path("spec").path("visible").asText())
                                : !row.path("spec").path("hidden").asBoolean()))
                .distinct(row -> row.path("metadata").path("name").asText())
                .reduceWith(() -> new long[2], (counts, row) -> {
                    if (row.path("metadata").path("name").asText().isBlank())
                        throw new IllegalStateException("AI_SUMMARY_SOURCE_INVALID");
                    Instant time =
                            Instant.parse(row.path("spec").path(timeField).asText());
                    counts[0]++;
                    if (!time.isBefore(window.start()) && time.isBefore(window.end())) counts[1]++;
                    return counts;
                })
                .map(counts -> new Count(counts[0], counts[1]))
                .onErrorReturn(Count.unavailable());
    }

    public String statistics(String current) {
        String result = "- 博客文章：" + posts.total() + " 篇（" + current + "新增 "
                + posts.articles().size() + " 篇）\n";
        result += "- 瞬间动态："
                + (moments.available()
                        ? moments.total() + " 条（" + current + "新增 " + moments.added() + " 条）"
                        : "暂不可用（查询失败或权限不足）")
                + "\n";
        if (comments.available() && replies.available())
            result += "- 评论总数：" + (comments.total() + replies.total()) + " 条（" + current + "新增 "
                    + (comments.added() + replies.added()) + " 条，含楼中楼）";
        else
            result +=
                    "- 评论总数：暂不可用（" + (comments.available() ? "楼中楼" : replies.available() ? "评论" : "评论及楼中楼") + "查询未完成）";
        return result;
    }

    public String activities() {
        var rows = new java.util.ArrayList<String>();
        if (!posts.articles().isEmpty()) rows.add("- 发布 " + posts.articles().size() + " 篇博客文章");
        if (moments.available() && moments.added() > 0) rows.add("- 发布 " + moments.added() + " 条瞬间动态");
        return rows.isEmpty() ? "- 本期暂无可核实的新增发布事项" : String.join("\n", rows);
    }

    public static boolean requested(String template) {
        return template.contains("{站点统计}") || template.contains("{完成事项}");
    }

    public static String current(String title) {
        return title.contains("日") ? "今日" : title.contains("月") ? "本月" : "本周";
    }

    public static String next(String title) {
        return title.contains("日") ? "明天" : title.contains("月") ? "下月" : "下周";
    }
}
