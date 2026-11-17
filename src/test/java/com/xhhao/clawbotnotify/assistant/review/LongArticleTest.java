package com.xhhao.clawbotnotify.assistant.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class LongArticleTest {
    @Test
    void unicodePartsReassembleExactlyAndVersionIncludesCompleteTail() {
        String content = ("中文😀\n".repeat(6000));
        var parts = DraftReview.parts(content);
        assertThat(String.join("", parts)).isEqualTo(content);
        assertThat(parts).allSatisfy(part -> {
            assertThat(DraftReview.count(part)).isLessThanOrEqualTo(1200);
            assertThat(Character.isLowSurrogate(part.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(part.charAt(part.length() - 1)))
                    .isFalse();
        });
        var one =
                new AssistantDrafts.Draft("post_create_draft", Map.of("content", content, "title", "标题"), null, false);
        var input = new LinkedHashMap<String, Object>();
        input.put("title", "标题");
        input.put("content", content);
        assertThat(DraftReview.version(one))
                .isEqualTo(DraftReview.version(new AssistantDrafts.Draft(one.tool(), input, null, false)));
        assertThat(DraftReview.version(one))
                .isNotEqualTo(DraftReview.version(new AssistantDrafts.Draft(
                        one.tool(), Map.of("content", content + "尾", "title", "标题"), null, false)));
    }

    @Test
    void diffReconstructsBothVersionsAndPreservesUnchangedLines() {
        String before = "首行\n删除\n保留\n旧尾";
        String after = "首行\n新增\n保留\n新尾";
        var diff = DraftReview.diff(before, after);
        assertThat(diff.stream()
                        .filter(c -> !c.kind().equals("ADDED"))
                        .map(DraftReview.Change::text)
                        .reduce("", String::concat))
                .isEqualTo(before);
        assertThat(diff.stream()
                        .filter(c -> !c.kind().equals("REMOVED"))
                        .map(DraftReview.Change::text)
                        .reduce("", String::concat))
                .isEqualTo(after);
        assertThat(diff).contains(new DraftReview.Change("UNCHANGED", "保留\n"));
        var large = DraftReview.diff("原\n".repeat(2000), "新\n".repeat(2000));
        assertThat(large).hasSize(2);
    }

    @Test
    void appendAndExactReplacementKeepUntouchedTextAndRejectAmbiguousMatches() {
        var input = Map.<String, Object>of("title", "标题", "content", "开头\n" + "正文😀".repeat(3000) + "\n尾段");
        var appended = DraftEdits.apply(input, Map.of("append", "\n续写"));
        assertThat(appended.get("content")).isEqualTo(input.get("content") + "\n续写");
        var replaced = DraftEdits.apply(appended, Map.of("find", "尾段", "replace", "新结尾"));
        assertThat(replaced.get("content"))
                .isEqualTo(appended.get("content").toString().replace("尾段", "新结尾"));
        assertThatThrownBy(() -> DraftEdits.apply(input, Map.of("find", "正文", "replace", "替换")))
                .hasMessage("AI_EDIT_TARGET_AMBIGUOUS");
        assertThatThrownBy(() -> DraftEdits.apply(input, Map.of("content", "新正文", "append", "后缀")))
                .hasMessage("AI_TOOL_INPUT_INVALID");
    }

    @Test
    void readAnyPartAndDiffWithoutClaimThenConfirmWritesTheEntireFrozenVersion() throws Exception {
        var f = new AssistantFixture();
        String content = "😀文章\n".repeat(5000);
        var source = f.command("preview-long", "写长文");
        f.drafts
                .stage(
                        source,
                        "post_create_draft",
                        Map.of("title", "长文章", "content", content),
                        null,
                        false,
                        Map.of("content", "旧正文", "title", "旧标题"))
                .block();
        var draft = f.drafts.load(f.binding, source.getMetadata().getName()).block();
        var reader = new AssistantPreviewReader(f.resources.client, f.secrets, f.drafts, f.authority);
        for (int page : List.of(2, 0, -2)) {
            var request = f.command("read-" + page, "查看");
            request.getSpec().setType(InboundCommand.Type.AI_PREVIEW);
            request.getSpec().setPage(page);
            request.getSpec().setPreviewOf(source.getMetadata().getName());
            reader.process(f.binding, request).block();
            assertThat(f.secrets.read(request.getSpec().getBodyRef()).block().get("aiResponse"))
                    .contains(DraftReview.version(draft));
        }
        assertThat(f.secrets.read(source.getSpec().getBodyRef()).block()).doesNotContainKey("proposalClaim");
        verifyNoInteractions(f.model, f.cli);
        var wrong = confirmation(f, "wrong", source, "确认保存");
        assertThat(f.confirmations.process(f.binding, wrong).block().getSpec().getErrorCode())
                .isEqualTo("AI_CONFIRMATION_MISMATCH");
        AtomicReference<List<String>> dispatched = new AtomicReference<>();
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.cli.run(argThat(a -> a.getFirst().equals("post")))).thenAnswer(call -> {
            dispatched.set(call.getArgument(0));
            var response = new ObjectMapper().createObjectNode();
            response.putObject("metadata").put("name", "saved-post");
            response.putObject("spec")
                    .put("publish", false)
                    .put(
                            "slug",
                            dispatched.get().stream()
                                    .filter(arg -> arg.startsWith("--slug="))
                                    .findFirst()
                                    .orElseThrow()
                                    .substring(7));
            return Mono.just(response);
        });
        var exact = confirmation(f, "exact", source, AssistantDrafts.confirmation(draft));
        assertThat(f.confirmations.process(f.binding, exact).block().getSpec().getWriteState())
                .isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(dispatched.get()).contains("--content=" + content);
        assertThat(f.confirmations
                        .process(f.binding, confirmation(f, "again", source, AssistantDrafts.confirmation(draft)))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("AI_PREVIEW_ALREADY_USED");
        verifyNoInteractions(f.model);
    }

    @Test
    void incrementalModelRevisionInvalidatesOldVersionAndRetainsTheFullBaseline() throws Exception {
        var f = new AssistantFixture();
        String original = "原文😀".repeat(2000);
        var source = f.command("old", "写文章");
        f.drafts
                .stage(source, "post_create_draft", Map.of("title", "标题", "content", original), null, false)
                .block();
        when(f.model.generate(any(), any())).thenAnswer(call -> {
            run.halo.aifoundation.chat.GenerateTextRequest request = call.getArgument(1);
            return request.getTools()
                    .getFirst()
                    .getExecutor()
                    .execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
                            .toolName("preview_revise")
                            .toolCallId("append")
                            .input(Map.of("append", "\n新结尾"))
                            .build())
                    .map(result -> run.halo.aifoundation.chat.GenerateTextResult.builder()
                            .text("已追加")
                            .build());
        });
        var revised = f.command("new", "继续写结尾");
        revised.getSpec().setPreviewOf("old");
        revised = f.resources.put(revised, false);
        assertThat(f.service.process(f.binding, revised).block().getSpec().getWriteState())
                .isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        var next = f.drafts.load(f.binding, "new").block();
        assertThat(next.input().get("content")).isEqualTo(original + "\n新结尾");
        assertThat(f.drafts
                        .input(f.secrets
                                .read(revised.getSpec().getBodyRef())
                                .block()
                                .get("proposalBefore"))
                        .get("content"))
                .isEqualTo(original);
        assertThatThrownBy(() -> f.drafts.load(f.binding, "old").block()).hasMessage("AI_PREVIEW_ALREADY_USED");
        verifyNoInteractions(f.cli, f.halo);
    }

    @Test
    void consoleDetailReturnsExactFullPartsAndDoesNotExposeOldGenerationContent() throws Exception {
        var f = new AssistantFixture();
        String id = "in-" + "a".repeat(64), content = "完整原文😀\n".repeat(2500);
        var command = f.command(id, "文章操作");
        f.drafts
                .stage(
                        command,
                        "post_update_draft",
                        Map.of("name", "post", "title", "新标题", "content", content),
                        "snapshot",
                        false,
                        Map.of("title", "旧标题", "content", "旧原文"))
                .block();
        var bindings = mock(com.xhhao.clawbotnotify.binding.BindingService.class);
        when(bindings.owned("admin")).thenReturn(Mono.just(f.binding));
        var service = new com.xhhao.clawbotnotify.command.CommandRecordService(
                f.resources.client,
                bindings,
                new com.xhhao.clawbotnotify.comment.CommentLinkRepository(f.resources.client),
                f.secrets,
                f.comments);
        var detail = service.detail("admin", id).block();
        assertThat(String.join("", detail.preview().parts())).isEqualTo(content);
        assertThat(detail.preview().beforeTitle()).isEqualTo("旧标题");
        assertThat(detail.preview().changes()).anySatisfy(change -> {
            assertThat(change.kind()).isEqualTo("REMOVED");
            assertThat(change.text()).isEqualTo("旧原文");
        });
        f.binding.getSpec().setGeneration(4);
        detail = service.detail("admin", id).block();
        assertThat(detail.preview()).isNull();
        assertThat(detail.contentErrorCode()).isEqualTo("HISTORICAL_GENERATION");
    }

    private InboundCommand confirmation(AssistantFixture f, String id, InboundCommand source, String body) {
        var command = f.command(id, body);
        command.getSpec().setPreviewOf(source.getMetadata().getName());
        command.getSpec().setType(InboundCommand.Type.AI_CONFIRM);
        return f.resources.put(command, false);
    }
}
