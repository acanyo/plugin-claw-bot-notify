package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.*;
import run.halo.aifoundation.message.*;
import run.halo.aifoundation.tool.*;

class AssistantGenerationPolicyTest {
    @Test
    void onlyFinalStepTextIsSentAndRedeliveryDoesNotCallModelAgain() throws Exception {
        var f = new AssistantFixture();
        when(f.model.generate(any(), any())).thenAnswer(call -> {
            GenerateTextRequest request = call.getArgument(1);
            assertThat(request.getPrepareStep()).isNull();
            assertThat(request.getMaxOutputTokens()).isNull();
            assertThat(request.getTimeouts()).isNull();
            return Mono.just(GenerateTextResult.builder()
                    .text("我来先获取真实数据。最终周报")
                    .finishReason(FinishReason.STOP)
                    .steps(List.of(
                            GenerationStep.builder()
                                    .text("我来先获取真实数据。")
                                    .finishReason(FinishReason.TOOL_CALLS)
                                    .build(),
                            GenerationStep.builder()
                                    .text("最终周报：文章3篇，瞬间2条，评论234条。")
                                    .finishReason(FinishReason.STOP)
                                    .build()))
                    .build());
        });
        var command = f.command("report", "生成周报");
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        var values = f.secrets.read(command.getSpec().getBodyRef()).block();
        assertThat(values.get("aiResponse")).isEqualTo("最终周报：文章3篇，瞬间2条，评论234条。");
        assertThat(values).containsEntry("generationFinishReason", "STOP").containsEntry("generationSteps", "2");
        f.service.process(f.binding, done).block();
        verify(f.model, times(1)).generate(any(), any());
        verifyNoInteractions(f.cli);
    }

    @Test
    void missingToolResultIsPartialInsteadOfReturningPreambleAsSuccess() throws Exception {
        var f = new AssistantFixture();
        var tool = ToolCall.builder()
                .toolCallId("comment-query")
                .toolName("halo_cli")
                .input(Map.of("arguments", List.of("comment", "list")))
                .build();
        when(f.model.generate(any(), any()))
                .thenReturn(Mono.just(GenerateTextResult.builder()
                        .text("我来先获取真实数据，再按格式生成周报。")
                        .finishReason(FinishReason.TOOL_CALLS)
                        .responseMessages(List.of(
                                ModelMessage.assistant(
                                        List.of(ModelMessagePart.text("先查询"), ModelMessagePart.toolCall(tool))),
                                ModelMessage.tool(List.of(ModelMessagePart.toolResult(ToolResult.builder()
                                        .toolCallId("unrelated")
                                        .toolName("halo_cli")
                                        .result(Map.of())
                                        .build())))))
                        .build()));
        var done = f.service.process(f.binding, f.command("unfinished", "周报")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.PARTIAL);
        assertThat(done.getSpec().getErrorCode()).isEqualTo("AI_GENERATION_INCOMPLETE");
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("任务尚未完成")
                .doesNotContain("我来先获取真实数据");
        verify(f.model, times(1)).generate(any(), any());
    }

    @Test
    void lengthAndMissingAnswersCannotBeMarkedComplete() throws Exception {
        for (FinishReason reason : List.of(FinishReason.LENGTH, FinishReason.CONTENT_FILTER, FinishReason.ERROR)) {
            var f = new AssistantFixture();
            when(f.model.generate(any(), any()))
                    .thenReturn(Mono.just(GenerateTextResult.builder()
                            .text("未完成正文")
                            .finishReason(reason)
                            .build()));
            var done =
                    f.service.process(f.binding, f.command("limited", "生成内容")).block();
            assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.PARTIAL);
            assertThat(done.getSpec().getErrorCode()).isIn("AI_PROVIDER_OUTPUT_LIMIT", "AI_GENERATION_INCOMPLETE");
        }
        assertThat(AssistantGenerationPolicy.answer(GenerateTextResult.builder()
                                .text("")
                                .finishReason(FinishReason.STOP)
                                .build())
                        .errorCode())
                .isEqualTo("AI_EMPTY_RESULT");
    }

    @Test
    void savedPreviewRemainsAvailableWhenLastStepWasATool() throws Exception {
        var f = new AssistantFixture();
        when(f.model.generate(any(), any())).thenAnswer(call -> {
            var command =
                    f.resources.client.get(InboundCommand.class, "preview").block();
            return f.drafts
                    .stage(command, "moment_create", Map.of("content", "准备发布", "visible", "PRIVATE"), null, false)
                    .thenReturn(GenerateTextResult.builder()
                            .text("先处理")
                            .finishReason(FinishReason.TOOL_CALLS)
                            .build());
        });
        var done = f.service.process(f.binding, f.command("preview", "发瞬间")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        assertThat(done.getSpec().getErrorCode()).isNull();
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("待确认", "准备发布");
        verifyNoInteractions(f.cli);
    }
}
