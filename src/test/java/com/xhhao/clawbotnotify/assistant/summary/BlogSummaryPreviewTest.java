package com.xhhao.clawbotnotify.assistant.summary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.tool.ToolExecutionContext;

class BlogSummaryPreviewTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String, Object> input = Map.of(
            "posts",
            List.of("first", "second"),
            "summary",
            "本周分享了安装与插件开发经验。",
            "period",
            "2026-11-09 至 2026-11-15（Asia/Shanghai）");

    ObjectNode post(String name) {
        var output = json.createObjectNode();
        var post = output.putObject("post");
        post.putObject("metadata").put("name", name);
        post.putObject("spec")
                .put("title", "文章 " + name)
                .put("publish", true)
                .put("visible", "PUBLIC")
                .put("deleted", false)
                .put("headSnapshot", "v1")
                .put("releaseSnapshot", "v1");
        post.putObject("status").put("phase", "PUBLISHED").put("permalink", "/archives/" + name);
        output.putObject("content").put("raw", "真实文章内容");
        return output;
    }

    AssistantFixture fixture() throws Exception {
        var f = new AssistantFixture();
        f.config.setBlogSummaryTemplate("{标题}\n{时间范围}\n\n{摘要}\n\n{文章列表}\n\n{标签}");
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.cli.run(List.of("post", "get", "first"))).thenReturn(Mono.just(post("first")));
        when(f.cli.run(List.of("post", "get", "second"))).thenReturn(Mono.just(post("second")));
        return f;
    }

    AssistantDrafts.Draft proposal(AssistantFixture f, InboundCommand command) {
        return f.drafts.decode(
                f.secrets.read(command.getSpec().getBodyRef()).block().get("proposal"));
    }

    @Test
    void modelCallbackCreatesFormattedPreviewWithRealLinksAndNoWrites() throws Exception {
        var f = fixture();
        f.config.setBlogSummaryTemplate("【{标题}】\n{时间范围}\n{摘要}\n{文章列表}\n{标签}");
        f.config.setBlogSummaryVisible("PRIVATE");
        when(f.model.generate(any(), any())).thenAnswer(call -> {
            GenerateTextRequest request = call.getArgument(1);
            var tool = request.getTools().stream()
                    .filter(t -> t.getName().equals("blog_summary_preview"))
                    .findFirst()
                    .orElseThrow();
            return tool.getExecutor()
                    .execute(ToolExecutionContext.builder()
                            .toolName(tool.getName())
                            .toolCallId("summary")
                            .input(input)
                            .build())
                    .thenReturn(GenerateTextResult.builder().text("已发布").build());
        });
        var command = f.command("summary-model", "把本周博客总结成瞬间");
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        var preview = proposal(f, done);
        assertThat(preview.tool()).isEqualTo("moment_create");
        assertThat(preview.input()).containsEntry("visible", "PRIVATE");
        assertThat(preview.input().get("content").toString())
                .isEqualTo(
                        "【博客小结】\n2026-11-09 至 2026-11-15（Asia/Shanghai）\n本周分享了安装与插件开发经验。\n• [文章 first](http://localhost:18091/archives/first)\n• [文章 second](http://localhost:18091/archives/second)\n#博客小结");
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("尚未写入", "确认发布", "PRIVATE");
        verify(f.cli, never()).run(argThat(args -> args.getFirst().equals("moment")));
    }

    @Test
    void perRequestFormatOverridesDefaultsAndReplacementValuesAreNotExpandedAgain() throws Exception {
        var f = fixture();
        f.config.setBlogSummaryIncludeLinks(false);
        var command = f.command("custom", "使用特别格式");
        f.tools
                .execute(
                        f.binding,
                        command,
                        "blog_summary_preview",
                        Map.of(
                                "posts",
                                List.of("first"),
                                "summary",
                                "保留字面 {标题} 与 $1",
                                "template",
                                "{摘要}\n{文章列表}",
                                "visible",
                                "PRIVATE"))
                .block();
        assertThat(proposal(f, command).input())
                .containsEntry("visible", "PRIVATE")
                .containsEntry("content", "保留字面 {标题} 与 $1\n• 文章 first");
        assertThat(f.config.getBlogSummaryVisible()).isEqualTo("PUBLIC");
        assertThat(f.config.getBlogSummaryTemplate()).contains("{标题}");
    }

    @Test
    void draftsPrivateDeletedAndUnpublishedEditsNeverProduceSummaryPreviews() throws Exception {
        for (String condition : List.of("draft", "private", "deleted", "modified", "wrong-name")) {
            var f = fixture();
            var source = post("first");
            var spec = (ObjectNode) source.path("post").path("spec");
            switch (condition) {
                case "draft" -> spec.put("publish", false);
                case "private" -> spec.put("visible", "PRIVATE");
                case "deleted" -> spec.put("deleted", true);
                case "modified" -> spec.put("headSnapshot", "v2");
                default -> ((ObjectNode) source.path("post").path("metadata")).put("name", "other");
            }
            when(f.cli.run(List.of("post", "get", "first"))).thenReturn(Mono.just(source));
            var command = f.command(condition, "总结");
            assertThatThrownBy(() -> f.tools
                            .execute(f.binding, command, "blog_summary_preview", input)
                            .block())
                    .hasMessage("AI_SUMMARY_POST_UNAVAILABLE");
            assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).doesNotContainKey("proposal");
            verify(f.cli, never()).run(argThat(args -> args.getFirst().equals("moment")));
        }
    }

    @Test
    void disabledWritesAndWrongSiteAreRejectedBeforeReadingPosts() throws Exception {
        var f = fixture();
        var command = f.command("blocked", "总结");
        f.config.setAllowWrites(false);
        assertThat(f.tools.definitions(f.binding, command, false))
                .noneMatch(t -> t.getName().equals("blog_summary_preview"));
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "blog_summary_preview", input)
                        .block())
                .hasMessage("AI_WRITES_DISABLED");
        verifyNoInteractions(f.cli);
        f.config.setAllowWrites(true);
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://other.invalid\"}")));
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "blog_summary_preview", input)
                        .block())
                .hasMessage("CLI_SITE_MISMATCH");
        verify(f.cli, never()).run(argThat(args -> args.getFirst().equals("post")));
    }

    @Test
    void invalidInputsAndOversizeOutputDoNotStageOrTruncateContent() throws Exception {
        var f = fixture();
        var command = f.command("invalid-summary", "总结");
        for (var bad : List.of(
                Map.of("posts", List.of(), "summary", "空范围"),
                Map.of("posts", List.of("first", "first"), "summary", "重复"),
                Map.of("posts", List.of("--help"), "summary", "无效"),
                Map.of("posts", List.of("first"), "summary", "摘要", "template", "只有标题"))) {
            assertThatThrownBy(() -> f.tools
                            .execute(f.binding, command, "blog_summary_preview", bad)
                            .block())
                    .hasMessage("AI_TOOL_INPUT_INVALID");
        }
        assertThatThrownBy(() -> f.tools
                        .execute(
                                f.binding,
                                command,
                                "blog_summary_preview",
                                Map.of("posts", List.of("first"), "summary", "字".repeat(2000)))
                        .block())
                .hasMessage("AI_PREVIEW_TOO_LONG");
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).doesNotContainKey("proposal");
    }

    @Test
    void momentLimitCountsEmojiAsOneCharacterAndNeverTruncates() throws Exception {
        var f = fixture();
        f.config.setBlogSummaryTemplate("{摘要}");
        var command = f.command("emoji-summary", "总结");
        String summary = "🌟".repeat(2000);
        f.tools
                .execute(
                        f.binding,
                        command,
                        "blog_summary_preview",
                        Map.of("posts", List.of("first"), "summary", summary))
                .block();
        var draft = proposal(f, command);
        assertThat(draft.input()).containsEntry("content", summary);
        assertThatThrownBy(() -> com.xhhao.clawbotnotify.assistant.cli.HaloCliPolicy.invocation(
                        "moment_create", Map.of("content", summary + "🌟"), "too-long"))
                .hasMessage("AI_TOOL_INPUT_INVALID");
    }

    @Test
    void missingOrUnsafeLinksCanBeExcludedWithoutInventingAUrl() throws Exception {
        var f = fixture();
        var source = post("first");
        ((ObjectNode) source.path("post").path("status")).put("permalink", "javascript:alert(1)");
        when(f.cli.run(List.of("post", "get", "first"))).thenReturn(Mono.just(source));
        var command = f.command("link-invalid", "总结");
        var selected = Map.<String, Object>of("posts", List.of("first"), "summary", "真实摘要");
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "blog_summary_preview", selected)
                        .block())
                .hasMessage("AI_SUMMARY_LINK_UNAVAILABLE");
        f.config.setBlogSummaryIncludeLinks(false);
        f.tools.execute(f.binding, command, "blog_summary_preview", selected).block();
        assertThat(proposal(f, command).input().get("content").toString())
                .contains("文章 first")
                .doesNotContain("javascript", "http");
    }

    @Test
    void confirmationUsesFrozenFormatAndVisibilityAfterSettingsChangeWithoutCallingModel() throws Exception {
        var f = fixture();
        f.config.setBlogSummaryVisible("PRIVATE");
        var source = f.command("frozen-summary", "总结");
        f.tools.execute(f.binding, source, "blog_summary_preview", input).block();
        var original = proposal(f, source);
        f.config.setBlogSummaryVisible("PUBLIC");
        f.config.setBlogSummaryTemplate("修改后的格式 {摘要}");
        var called = new AtomicReference<List<String>>();
        when(f.moments.publishCli(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            List<String> args = call.getArgument(2);
            called.set(args);
            String name = args.stream()
                    .filter(a -> a.startsWith("--name="))
                    .findFirst()
                    .orElseThrow()
                    .substring(7);
            return Mono.just(
                    json.readTree("{\"metadata\":{\"name\":\"" + name + "\"},\"spec\":{\"visible\":\"PRIVATE\"}}"));
        });
        var confirm = f.command("confirm-summary", "确认发布");
        confirm.getSpec().setType(InboundCommand.Type.AI_CONFIRM);
        confirm.getSpec().setPreviewOf(source.getMetadata().getName());
        var done = f.confirmations.process(f.binding, confirm).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(called.get()).contains("--visible=PRIVATE");
        assertThat(called.get().toString())
                .contains("<a href=\"http://localhost:18091/archives/first\"")
                .doesNotContain("修改后的格式");
        assertThat(original.input().get("content").toString()).contains("本周分享");
        f.confirmations.process(f.binding, done).block();
        verify(f.moments, times(1)).publishCli(eq("private-pat"), anyString(), anyList(), any());
        verifyNoInteractions(f.model);
    }
}
