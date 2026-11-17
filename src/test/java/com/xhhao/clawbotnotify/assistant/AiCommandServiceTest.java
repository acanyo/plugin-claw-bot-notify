package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.message.ModelMessagePart;
import run.halo.aifoundation.tool.ToolCall;
import run.halo.aifoundation.tool.ToolExecutionContext;
import run.halo.aifoundation.tool.ToolResult;

class AiCommandServiceTest {
    @Test
    void modelGetsTypedToolsNoRetriesAndFullConversationIncludingToolPairs() throws Exception {
        var f = new AssistantFixture();
        var call = ToolCall.builder()
                .toolCallId("call")
                .toolName("post_list")
                .input(Map.of())
                .build();
        var result = ToolResult.builder()
                .toolCallId("call")
                .toolName("post_list")
                .result(Map.of("items", List.of()))
                .build();
        var messages = List.of(
                ModelMessage.assistant(List.of(ModelMessagePart.toolCall(call))),
                ModelMessage.tool(List.of(ModelMessagePart.toolResult(result))),
                ModelMessage.assistant("可以这样写：今天上线了新功能"));
        var captured = new AtomicReference<GenerateTextRequest>();
        when(f.model.generate(any(), any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(1);
            captured.set(request);
            return Mono.just(GenerateTextResult.builder()
                    .text("可以这样写：今天上线了新功能")
                    .responseMessages(messages)
                    .build());
        });
        var first = f.command("first", "帮我写一条瞬间文案");
        assertThat(f.service.process(f.binding, first).block().getSpec().getWriteState())
                .isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(captured.get().getMaxRetries()).isZero();
        assertThat(captured.get().getTools())
                .extracting(tool -> tool.getName())
                .contains("halo_cli_help", "halo_cli", "post_list", "moment_create");
        var second = f.command("second", "改短一点");
        f.service.process(f.binding, second).block();
        assertThat(captured.get().getMessages()).hasSize(5);
        assertThat(captured.get().getMessages().get(1).getContent().getFirst().getToolCallId())
                .isEqualTo("call");
        assertThat(captured.get().getMessages().get(2).getContent().getFirst().getToolCallId())
                .isEqualTo("call");
        assertThat(captured.get()
                        .getMessages()
                        .getLast()
                        .getContent()
                        .getFirst()
                        .getText())
                .isEqualTo("改短一点");
        verifyNoInteractions(f.cli);
    }

    void writeModel(AssistantFixture f, boolean failAfterWrite) throws Exception {
        var json = new ObjectMapper();
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.cli.run(argThat(args -> args.getFirst().equals("moment")))).thenAnswer(invocation -> {
            List<String> args = invocation.getArgument(0);
            String name = args.stream()
                    .filter(a -> a.startsWith("--name="))
                    .findFirst()
                    .orElseThrow()
                    .substring(7);
            return Mono.just(
                    json.readTree("{\"metadata\":{\"name\":\"" + name + "\"},\"spec\":{\"visible\":\"PUBLIC\"}}"));
        });
        when(f.model.generate(any(), any())).thenAnswer(invocation -> {
            GenerateTextRequest request = invocation.getArgument(1);
            var tool = request.getTools().stream()
                    .filter(t -> t.getName().equals("moment_create"))
                    .findFirst()
                    .orElseThrow();
            return tool.getExecutor()
                    .execute(ToolExecutionContext.builder()
                            .toolName("moment_create")
                            .toolCallId("one")
                            .input(Map.of("content", "今天上线了新功能"))
                            .build())
                    .flatMap(output -> failAfterWrite
                            ? Mono.error(new IllegalArgumentException("private-provider-key"))
                            : Mono.just(
                                    GenerateTextResult.builder().text("已发布瞬间").build()));
        });
    }

    @Test
    void modelToolCallbackOnlyPreviewsAndRedeliveryDoesNotRepeat() throws Exception {
        var f = new AssistantFixture();
        writeModel(f, false);
        var command = f.command("create", "发一条瞬间：今天上线了新功能");
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("待确认", "今天上线了新功能", "尚未写入")
                .doesNotContain("已发布瞬间");
        f.service.process(f.binding, done).block();
        verify(f.model, times(1)).generate(any(), any());
        verify(f.cli, never()).run(argThat(args -> args.getFirst().equals("moment")));
    }

    @Test
    void aModelFailureAfterStagingPreservesThePreviewWithoutWriting() throws Exception {
        var f = new AssistantFixture();
        writeModel(f, true);
        var done = f.service.process(f.binding, f.command("after-write", "发瞬间")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        String response = f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse");
        assertThat(response).contains("待确认", "尚未写入").doesNotContain("private-provider-key");
        f.service.process(f.binding, done).block();
        verify(f.model, times(1)).generate(any(), any());
    }

    @Test
    void anUnavailableWriteProcessIsNotCalledBeforeConfirmation() throws Exception {
        var f = new AssistantFixture();
        writeModel(f, false);
        doReturn(Mono.error(new IllegalStateException("CLI_TIMEOUT")))
                .when(f.cli)
                .run(argThat(args -> args.getFirst().equals("moment")));
        var done =
                f.service.process(f.binding, f.command("lost-response", "发瞬间")).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("待确认", "尚未写入");
        f.service.process(f.binding, done).block();
        verify(f.cli, never()).run(argThat(args -> args.getFirst().equals("moment")));
    }

    @Test
    void restartWithAnExecutingCommandNeverCallsTheModelAgain() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("interrupted", "发瞬间");
        command.getSpec().setWriteState(InboundCommand.WriteState.EXECUTING);
        command = f.resources.put(command, false);
        f.service.process(f.binding, command).block();
        verifyNoInteractions(f.model, f.cli);
        f.secrets
                .update(command.getSpec().getBodyRef(), data -> {
                    data.put("aiResponse", "已完成");
                    data.put("aiState", "SUCCEEDED");
                })
                .block();
        assertThat(f.service.process(f.binding, command).block().getSpec().getWriteState())
                .isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void recoveredInterruptedWriteIncludesResourceNameWithoutCallingModelOrCli() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("recovered", "发瞬间");
        command.getSpec().setWriteState(InboundCommand.WriteState.UNKNOWN);
        command = f.resources.put(command, false);
        f.secrets
                .update(command.getSpec().getBodyRef(), data -> {
                    data.put("mutation", "claimed");
                    data.put("mutationName", "clawbot-interrupted");
                })
                .block();
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.UNKNOWN);
        assertThat(f.secrets.read(done.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("clawbot-interrupted", "结果未知");
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void invalidSettingsAndExpiredOrRevokedCommandsFailBeforeModelGeneration() throws Exception {
        var f = new AssistantFixture();
        when(f.settings.get()).thenReturn(Mono.error(new IllegalStateException("AI_SETTINGS_INVALID")));
        assertThat(f.service
                        .process(f.binding, f.command("invalid", "你好"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("AI_SETTINGS_INVALID");
        when(f.authority.check(any())).thenReturn(Mono.error(new IllegalStateException("BINDING_CHANGED")));
        assertThat(f.service
                        .process(f.binding, f.command("revoked", "你好"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("BINDING_CHANGED");
        var expired = f.command("expired", "你好");
        expired.getSpec().setReceivedAt(java.time.Instant.now().minusSeconds(90000));
        assertThat(f.service.process(f.binding, expired).block().getSpec().getErrorCode())
                .isEqualTo("COMMAND_EXPIRED");
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void resetClearsHistoryWithoutModelOrCli() throws Exception {
        var f = new AssistantFixture();
        f.history.messages(f.binding, f.config).block();
        f.history
                .append(f.binding, f.config, "old", "旧问题", List.of(ModelMessage.assistant("旧答复")))
                .block();
        var command = f.command("reset", "新对话");
        command.getSpec().setType(InboundCommand.Type.AI_RESET);
        var done = f.service.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.history.messages(f.binding, f.config).block()).isEmpty();
        verifyNoInteractions(f.model, f.cli);
    }
}
