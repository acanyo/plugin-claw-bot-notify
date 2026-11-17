package com.xhhao.clawbotnotify.assistant.summary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.assistant.AssistantGenerationRunner;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;

class SiteReportTest {
    final ObjectMapper json = new ObjectMapper();
    final Instant start = Instant.parse("2026-11-02T13:00:00Z");
    final Instant end = Instant.parse("2026-11-09T13:00:00Z");
    final BlogSummarySchedule.Window window =
            new BlogSummarySchedule.Window(start, end, "2026-11-02 至 2026-11-09（Asia/Shanghai）", "周报");

    ObjectNode resource(String kind, String name, Instant time, boolean publicResource) {
        var row = json.createObjectNode();
        row.putObject("metadata").put("name", name);
        var spec = row.putObject("spec");
        if (kind.equals("post")) {
            spec.put("title", "文章 " + name)
                    .put("publish", true)
                    .put("visible", publicResource ? "PUBLIC" : "PRIVATE")
                    .put("publishTime", time.toString())
                    .put("headSnapshot", "v1")
                    .put("releaseSnapshot", "v1");
            row.putObject("status").put("phase", "PUBLISHED").put("permalink", "/archives/" + name);
        } else {
            spec.put("approved", true).put(kind.equals("moment") ? "releaseTime" : "creationTime", time.toString());
            if (kind.equals("moment")) spec.put("visible", publicResource ? "PUBLIC" : "PRIVATE");
            else spec.put("hidden", !publicResource);
        }
        return row;
    }

    ObjectNode page(int number, boolean more, JsonNode... items) {
        var result =
                json.createObjectNode().put("page", number).put("hasNext", more).put("total", 9999);
        var rows = result.putArray("items");
        for (var item : items) rows.add(item);
        return result;
    }

    class Fixture {
        final AssistantFixture f = new AssistantFixture();
        final Map<List<String>, JsonNode> results = new HashMap<>();

        Fixture() throws Exception {
            results.put(List.of("auth", "current"), json.readTree("{\"baseUrl\":\"http://localhost:18091\"}"));
            var included = resource("post", "included", start, true);
            var old = resource("post", "old", start.minusSeconds(1), true);
            var boundary = resource("post", "boundary", end, true);
            results.put(List.of("post", "list", "--page=1", "--size=100"), page(1, true, included, old));
            results.put(
                    List.of("post", "list", "--page=2", "--size=100"),
                    page(2, false, boundary, resource("post", "private", start, false), included));
            var content = json.createObjectNode();
            content.set("post", included);
            content.putObject("content").put("raw", "公开博客正文，记录安装经验。");
            results.put(List.of("post", "get", "included"), content);
            results.put(
                    List.of("moment", "list", "--page=1", "--size=100"),
                    page(
                            1,
                            false,
                            resource("moment", "new-moment", start, true),
                            resource("moment", "old-moment", start.minusSeconds(1), true),
                            resource("moment", "private-moment", start, false)));
            results.put(
                    List.of("comment", "list", "--page=1", "--size=100"),
                    page(
                            1,
                            false,
                            resource("comment", "c1", start, true),
                            resource("comment", "c2", end, true),
                            resource("comment", "hidden", start, false)));
            results.put(
                    List.of("comment", "reply", "list", "--page=1", "--size=100"),
                    page(1, false, resource("reply", "r1", start, true)));
            when(f.cli.run(anyList())).thenAnswer(call -> {
                JsonNode result = results.get(call.getArgument(0));
                return result == null ? Mono.error(new IllegalStateException("CLI_PROCESS_FAILED")) : Mono.just(result);
            });
        }

        ScheduledBlogSummary service() {
            return new ScheduledBlogSummary(
                    f.authority,
                    f.settings,
                    f.tools,
                    new AssistantGenerationRunner(f.model),
                    f.drafts,
                    f.secrets,
                    f.resources.client);
        }

        InboundCommand scheduled(String name) {
            f.config.setBlogSummaryScheduleEnabled(true);
            var command = f.command(name, "定时报告");
            command.getSpec().setWriteState(InboundCommand.WriteState.EXECUTING);
            command.getSpec().setStartedAt(Instant.now());
            f.secrets
                    .update(
                            command.getSpec().getBodyRef(),
                            values -> values.put("scheduledScheduleKey", BlogSummarySchedule.identity(f.config)))
                    .block();
            return f.resources.put(command, false);
        }

        void model(String result) {
            when(f.model.generate(any(), any()))
                    .thenReturn(
                            Mono.just(GenerateTextResult.builder().text(result).build()));
        }
    }

    @Test
    void completePublicStatisticsUseActualDatesInsteadOfCliTotalsAndPrivateResources() throws Exception {
        var x = new Fixture();
        var report = x.f.tools.reportSources(x.f.command("stats", "统计"), window).block();
        assertThat(report.posts().total()).isEqualTo(3);
        assertThat(report.posts().articles())
                .extracting(BlogSummarySources.Article::name)
                .containsExactly("included");
        assertThat(report.statistics("本周"))
                .isEqualTo("- 博客文章：3 篇（本周新增 1 篇）\n- 瞬间动态：2 条（本周新增 1 条）\n- 评论总数：3 条（本周新增 2 条，含楼中楼）");
        assertThat(report.activities()).isEqualTo("- 发布 1 篇博客文章\n- 发布 1 条瞬间动态");
        verify(x.f.cli, times(1)).run(List.of("post", "list", "--page=1", "--size=100"));
        verify(x.f.cli, times(1)).run(List.of("post", "list", "--page=2", "--size=100"));
    }

    @Test
    void failedCommentQueriesAreUnavailableWhileSuccessfulEmptyResultsAreZero() throws Exception {
        var x = new Fixture();
        x.results.remove(List.of("comment", "reply", "list", "--page=1", "--size=100"));
        x.results.put(List.of("moment", "list", "--page=1", "--size=100"), page(1, false));
        var report = x.f.tools
                .reportSources(x.f.command("partial-stats", "统计"), window)
                .block();
        assertThat(report.statistics("本周")).contains("瞬间动态：0 条（本周新增 0 条）", "评论总数：暂不可用（楼中楼查询未完成）");
        assertThat(report.activities()).doesNotContain("测试", "草稿管理");
        verifyNoInteractions(x.f.model);
    }

    @Test
    void scheduledDefaultRendersRequestedLayoutWithOriginalAiWishAndServerOwnedFacts() throws Exception {
        var x = new Fixture();
        var captured = new AtomicReference<GenerateTextRequest>();
        when(x.f.model.generate(any(), any())).thenAnswer(call -> {
            captured.set(call.getArgument(1));
            return Mono.just(GenerateTextResult.builder()
                    .text("{\"summary\":\"分享安装经验。\",\"wish\":\"把日常的小光，写成未来的星河。\"}")
                    .build());
        });
        var done = x.service()
                .process(x.f.binding, x.scheduled("default-report"), window)
                .block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        var values = x.f.secrets.read(done.getSpec().getBodyRef()).block();
        var draft = x.f.drafts.decode(values.get("proposal"));
        assertThat(draft.input().get("content").toString())
                .isEqualTo(
                        "🤖 拾光 Bot 周报\n2026-11-02 至 2026-11-09（Asia/Shanghai）\n\n📊 本周数据：\n- 博客文章：3 篇（本周新增 1 篇）\n- 瞬间动态：2 条（本周新增 1 条）\n- 评论总数：3 条（本周新增 2 条，含楼中楼）\n\n✅ 完成事项：\n- 发布 1 篇博客文章\n- 发布 1 条瞬间动态\n\n💡 本周寄语：把日常的小光，写成未来的星河。\n\n🎯 下周继续守护林间拾语");
        assertThat(values.get("aiResponse")).contains("老板，", "确认发布");
        assertThat(captured.get().getTools()).isEmpty();
        assertThat(captured.get()
                        .getMessages()
                        .getFirst()
                        .getContent()
                        .getFirst()
                        .getText())
                .contains("公开博客正文", "verifiedActivities", "站点统计");
        verify(x.f.cli, never()).run(argThat(args -> args.getFirst().equals("moment") && args.contains("create")));
    }

    @Test
    void noNewArticlesStillProducesAReportWithZeroActivityAndAFreshWish() throws Exception {
        var x = new Fixture();
        x.results.put(List.of("post", "list", "--page=1", "--size=100"), page(1, false));
        x.results.put(List.of("moment", "list", "--page=1", "--size=100"), page(1, false));
        x.model("{\"summary\":\"本期暂无新增文章。\",\"wish\":\"静待花开，也珍惜每次记录。\"}");
        var command = x.scheduled("quiet-day");
        var daily = new BlogSummarySchedule.Window(start, end, "统计区间", "日报");
        var done = x.service().process(x.f.binding, command, daily).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        var values = x.f.secrets.read(done.getSpec().getBodyRef()).block();
        assertThat(values).doesNotContainKey("scheduledEmpty");
        assertThat(x.f.drafts
                        .decode(values.get("proposal"))
                        .input()
                        .get("content")
                        .toString())
                .contains("拾光 Bot 日报", "今日新增 0 篇", "暂无可核实的新增发布事项", "💡 今日寄语：静待花开", "明天继续守护");
        verify(x.f.model, times(1)).generate(any(), any());
    }

    @Test
    void invalidStructuredProseCannotCreateAPreview() throws Exception {
        for (String output : List.of(
                "不是JSON",
                "{\"summary\":\"内容\",\"wish\":\"\"}",
                "{\"summary\":\"内容\",\"wish\":\"寄语\",\"statistics\":\"虚构100篇\"}")) {
            var x = new Fixture();
            x.model(output);
            var done = x.service()
                    .process(x.f.binding, x.scheduled("bad-prose"), window)
                    .block();
            assertThat(done.getSpec().getErrorCode()).isEqualTo("AI_SUMMARY_FORMAT_INVALID");
            assertThat(x.f.secrets.read(done.getSpec().getBodyRef()).block()).doesNotContainKey("proposal");
        }
    }

    @Test
    void manualReportUsesTheSameRealStatisticsAndSupportsTheMonthLayout() throws Exception {
        var x = new Fixture();
        var command = x.f.command("manual-report", "按月报告");
        x.f.tools
                .execute(
                        x.f.binding,
                        command,
                        "blog_summary_preview",
                        Map.of(
                                "posts",
                                List.of("included"),
                                "summary",
                                "真实摘要",
                                "wish",
                                "慢慢生长，月月留光。",
                                "title",
                                "月报",
                                "start",
                                start.toString(),
                                "end",
                                end.toString()))
                .block();
        var draft = x.f.drafts.decode(
                x.f.secrets.read(command.getSpec().getBodyRef()).block().get("proposal"));
        assertThat(draft.input().get("content").toString())
                .contains("拾光 Bot 月报", "本月数据", "本月新增 1 篇", "本月寄语：慢慢生长", "下月继续守护");
        verifyNoInteractions(x.f.model);
    }
}
