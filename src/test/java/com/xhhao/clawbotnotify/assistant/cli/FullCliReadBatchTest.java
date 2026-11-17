package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.aifoundation.chat.*;
import run.halo.aifoundation.message.*;
import run.halo.aifoundation.tool.*;

class FullCliReadBatchTest {
    final FullCliToolsTest setup = new FullCliToolsTest();

    @Test
    void independentReadsStartTogetherAndResultsKeepInputOrderAndRedaction() throws Exception {
        var f = setup.fixture();
        var pending = List.of(
                Sinks.<HaloCliProcess.Result>one(),
                Sinks.<HaloCliProcess.Result>one(),
                Sinks.<HaloCliProcess.Result>one(),
                Sinks.<HaloCliProcess.Result>one());
        var started = new AtomicInteger();
        when(f.cli.invoke(anyList(), isNull()))
                .thenAnswer(call ->
                        Mono.defer(() -> pending.get(started.getAndIncrement()).asMono()));
        var commands = List.of(
                List.of("comment", "list", "--size=1"),
                List.of("comment", "list", "--size=2"),
                List.of("comment", "list", "--size=3"),
                List.of("comment", "list", "--size=4"));
        var command = f.command("batch", "读取多页");
        var future = f.tools
                .execute(f.binding, command, "halo_cli_batch", Map.of("commands", commands))
                .toFuture();
        assertThat(started).hasValue(4);
        for (int i = 3; i >= 0; i--)
            pending.get(i)
                    .tryEmitValue(new HaloCliProcess.Result(
                            0,
                            setup.json.readTree("{\"page\":" + i
                                    + ",\"total\":42,\"token\":\"private-token\",\"createdAt\":\"2026-11-17\"}"),
                            ""));
        var results = (List<Map<String, Object>>) ((Map<?, ?>) future.get()).get("results");
        assertThat(results).hasSize(4);
        for (int i = 0; i < 4; i++) {
            assertThat((List<String>) results.get(i).get("arguments")).contains("--size=" + (i + 1));
            assertThat(HaloCliOutput.pretty(results.get(i)))
                    .contains("42", "2026-11-17")
                    .doesNotContain("private-token");
        }
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).doesNotContainKeys("mutation", "proposal");
        verify(f.cli, times(1)).run(List.of("auth", "current"));
    }

    @Test
    void anyWriteOrInvalidOptionRejectsTheWholePlanBeforeAnyReadRuns() throws Exception {
        for (var bad : List.of(List.of("comment", "delete", "danger"), List.of("comment", "list", "--made-up"))) {
            var f = setup.fixture();
            var command = f.command("reject-batch", "混合查询");
            assertThatThrownBy(() -> f.tools
                            .execute(
                                    f.binding,
                                    command,
                                    "halo_cli_batch",
                                    Map.of("commands", List.of(List.of("comment", "list"), bad)))
                            .block())
                    .hasMessage(bad.contains("delete") ? "AI_BATCH_READ_ONLY" : "CLI_OPTION_INVALID");
            verify(f.cli, never()).invoke(anyList(), nullable(String.class));
            assertThat(f.secrets.read(command.getSpec().getBodyRef()).block())
                    .doesNotContainKeys("mutation", "proposal");
        }
    }

    @Test
    void oneFailedReadIsReportedWithoutDiscardingOrRepeatingSuccessfulReads() throws Exception {
        var f = setup.fixture();
        var count = new AtomicInteger();
        when(f.cli.invoke(anyList(), isNull()))
                .thenAnswer(call -> count.getAndIncrement() == 0
                        ? Mono.error(new IllegalStateException("CLI_FAILED"))
                        : Mono.just(new HaloCliProcess.Result(0, setup.json.readTree("{\"total\":7}"), "")));
        var output = f.tools
                .execute(
                        f.binding,
                        f.command("partial-batch", "读取两项"),
                        "halo_cli_batch",
                        Map.of("commands", List.of(List.of("comment", "list"), List.of("comment", "list", "--size=2"))))
                .block();
        assertThat(HaloCliOutput.pretty(output)).contains("CLI_FAILED", "7");
        assertThat(count).hasValue(2);
    }

    @Test
    void fourQueriesNeedOnlyOneToolRoundThenOneFinalAnswer() throws Exception {
        var f = setup.fixture();
        var commands = List.of(
                List.of("comment", "list", "--size=1"),
                List.of("comment", "list", "--size=2"),
                List.of("comment", "list", "--size=3"),
                List.of("comment", "list", "--size=4"));
        var input = Map.<String, Object>of("commands", commands);
        var count = new AtomicInteger();
        when(f.model.generate(any(), any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(1);
            if (count.getAndIncrement() == 1) {
                assertThat(request.getMessages()
                                .getLast()
                                .getContent()
                                .getFirst()
                                .getResult()
                                .toString())
                        .contains("results");
                return Mono.just(GenerateTextResult.builder()
                        .text("完整周报")
                        .finishReason(FinishReason.STOP)
                        .build());
            }
            var tool = request.getTools().stream()
                    .filter(t -> "halo_cli_batch".equals(t.getName()))
                    .findFirst()
                    .orElseThrow();
            return tool.getExecutor()
                    .execute(ToolExecutionContext.builder()
                            .toolName(tool.getName())
                            .toolCallId("batch-call")
                            .input(input)
                            .build())
                    .map(output -> GenerateTextResult.builder()
                            .finishReason(FinishReason.TOOL_CALLS)
                            .responseMessages(List.of(
                                    ModelMessage.assistant(List.of(ModelMessagePart.toolCall(ToolCall.builder()
                                            .toolCallId("batch-call")
                                            .toolName(tool.getName())
                                            .input(input)
                                            .build()))),
                                    ModelMessage.tool(List.of(ModelMessagePart.toolResult(ToolResult.builder()
                                            .toolCallId("batch-call")
                                            .toolName(tool.getName())
                                            .result(output)
                                            .build())))))
                            .build());
        });
        var done = f.service.process(f.binding, f.command("report-batch", "周报")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block())
                .containsEntry("generationSteps", "2")
                .containsEntry("aiResponse", "完整周报")
                .containsKey("generationRoundDurationMs");
        verify(f.model, times(2)).generate(any(), any());
        verify(f.cli, times(4)).invoke(anyList(), isNull());
    }
}
