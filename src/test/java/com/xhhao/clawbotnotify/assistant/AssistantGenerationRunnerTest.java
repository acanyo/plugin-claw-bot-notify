package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.*;
import run.halo.aifoundation.message.*;
import run.halo.aifoundation.tool.*;

class AssistantGenerationRunnerTest {
    private static GenerateTextResult query(int index, Object output) {
        var call = ToolCall.builder()
                .toolCallId("query-" + index)
                .toolName("halo_cli")
                .input(Map.of("arguments", List.of("comment", "list", "--page=" + index)))
                .build();
        var result = ToolResult.builder()
                .toolCallId(call.getToolCallId())
                .toolName(call.getToolName())
                .result(output)
                .build();
        return GenerateTextResult.builder()
                .text("先查询这一页")
                .finishReason(FinishReason.TOOL_CALLS)
                .responseMessages(List.of(
                        ModelMessage.assistant(List.of(ModelMessagePart.toolCall(call))),
                        ModelMessage.tool(List.of(ModelMessagePart.toolResult(result)))))
                .build();
    }

    @Test
    void fiftyToolRoundsReachTheFinalAnswerAndReplayCannotRunThemAgain() throws Exception {
        var f = new AssistantFixture();
        var count = new AtomicInteger();
        when(f.model.generate(any(), any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(1);
            int index = count.getAndIncrement();
            assertThat(request.getMaxOutputTokens()).isNull();
            assertThat(request.getPrepareStep()).isNull();
            assertThat(request.getTimeouts()).isNull();
            assertThat(request.getMaxRetries()).isZero();
            assertThat(request.getMessages()).hasSize(index * 2 + 1);
            assertThat(request.getSystem()).contains("所有写操作只生成预览");
            if (index > 0) {
                var part = request.getMessages().getLast().getContent().getFirst();
                assertThat(part.getToolCallId()).isEqualTo("query-" + (index - 1));
                assertThat(part.getResult()).isEqualTo(Map.of("page", index - 1));
            }
            return Mono.just(
                    index < 50
                            ? query(index, Map.of("page", index))
                            : GenerateTextResult.builder()
                                    .text("已查完50页，周报结果如下。")
                                    .finishReason(FinishReason.STOP)
                                    .responseMessages(List.of(ModelMessage.assistant("已查完50页，周报结果如下。")))
                                    .build());
        });
        var command = f.command("fifty-pages", "统计所有评论生成周报");
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block())
                .containsEntry("aiResponse", "已查完50页，周报结果如下。")
                .containsEntry("generationSteps", "51");
        assertThat(f.history.messages(f.binding, f.config).block()).hasSize(102);
        f.service.process(f.binding, done).block();
        verify(f.model, times(51)).generate(any(), any());
    }

    @Test
    void growingCurrentToolResultsStopAtContextCapacityWithoutAnotherProviderRequest() throws Exception {
        var f = new AssistantFixture();
        when(f.model.generate(any(), any())).thenReturn(Mono.just(query(1, "工具数据".repeat(40000))));
        var done =
                f.service.process(f.binding, f.command("too-much-data", "查询数据")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.PARTIAL);
        assertThat(done.getSpec().getErrorCode()).isEqualTo("AI_CONTEXT_LIMIT");
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("上下文上限", "未能完成")
                .doesNotContain("先查询");
        verify(f.model, times(1)).generate(any(), any());
        verifyNoInteractions(f.cli);
    }

    @Test
    void contextIncludesSystemToolsAndCurrentInputAndEvictsWholeOldToolTurns() {
        var old = new ArrayList<ModelMessage>();
        old.add(ModelMessage.user("旧问题😀"));
        old.addAll(query(1, "旧结果").getResponseMessages());
        old.add(ModelMessage.assistant("旧答复"));
        old.add(ModelMessage.user("保留的问题"));
        old.add(ModelMessage.assistant("保留的答复"));
        var current = List.of(ModelMessage.user("当前问题"));
        var tools = List.of(ToolDefinition.builder()
                .name("read")
                .description("工具说明😀")
                .inputSchema(Map.of("type", "object"))
                .executor(ctx -> Mono.just("unused"))
                .build());
        long required = AssistantContextBudget.overhead("系统提示", tools) + AssistantContextBudget.size(current);
        int capacity = (int) (required + AssistantContextBudget.size(old.subList(4, 6)));
        var window = AssistantContextBudget.window("系统提示", tools, old, current, capacity);
        assertThat(window).hasSize(3);
        assertThat(window.getFirst().getContent().getFirst().getText()).isEqualTo("保留的问题");
        assertThat(window.getLast().getContent().getFirst().getText()).isEqualTo("当前问题");
        assertThat(old).hasSize(6);
        assertThat(AssistantContextBudget.size("😀")).isEqualTo(3); // Two JSON quotes plus one Unicode character.
        assertThatThrownBy(() -> AssistantContextBudget.window("系统提示", tools, old, current, (int) required - 1))
                .hasMessage("AI_CONTEXT_LIMIT");
    }

    @Test
    void longInputAndFullAnswerHaveNoFormerMessageOrReplyQuota() throws Exception {
        var f = new AssistantFixture();
        String input = "输入😀".repeat(11000);
        String answer = "正文😀".repeat(2500) + "全文结尾";
        when(f.model.generate(any(), any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(1);
            assertThat(request.getMessages().getLast().getContent().getFirst().getText())
                    .isEqualTo(input);
            return Mono.just(GenerateTextResult.builder()
                    .text(answer)
                    .finishReason(FinishReason.STOP)
                    .build());
        });
        var done = f.service.process(f.binding, f.command("long-reply", input)).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block()).containsEntry("aiResponse", answer);
    }

    @Test
    void legacyQuotaFieldsAreIgnoredAndHistoryCanKeepMoreThanTwelveTurns() throws Exception {
        var config = new ObjectMapper().readValue("""
                {"enabled":true,"maxSteps":1,"maxOutputTokens":256,"historyTurns":1,"idleMinutes":5}
                """, AssistantSettings.Config.class);
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxContextChars()).isEqualTo(120000);
        var f = new AssistantFixture();
        f.history.messages(f.binding, config).block();
        for (int i = 0; i < 20; i++) {
            f.history
                    .append(f.binding, config, "turn-" + i, "问题" + i, List.of(ModelMessage.assistant("答复" + i)))
                    .block();
        }
        assertThat(f.history.messages(f.binding, config).block()).hasSize(40);
    }
}
