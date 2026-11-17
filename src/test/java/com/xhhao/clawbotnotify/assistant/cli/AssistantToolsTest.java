package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class AssistantToolsTest {
    final ObjectMapper json = new ObjectMapper();

    void profile(AssistantFixture f) throws Exception {
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091/\"}")));
    }

    @Test
    void aDifferentProfileSiteNeverExecutesTheOperation() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("different-site", "查询");
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:8090\"}")));
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "post_list", Map.of())
                        .block())
                .hasMessage("CLI_SITE_MISMATCH");
        verify(f.cli, times(1)).run(any());
    }

    @Test
    void disabledWritesOrRevokedBindingNeverLaunchesCli() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("no-write", "发瞬间");
        f.config.setAllowWrites(false);
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "moment_create", Map.of("content", "正文"))
                        .block())
                .hasMessage("AI_WRITES_DISABLED");
        verifyNoInteractions(f.cli);
        when(f.authority.check(any())).thenReturn(Mono.error(new IllegalStateException("HALO_ADMIN_REQUIRED")));
        assertThatThrownBy(() -> f.tools
                        .execute(f.binding, command, "post_list", Map.of())
                        .block())
                .hasMessage("HALO_ADMIN_REQUIRED");
        verifyNoInteractions(f.cli);
    }

    @Test
    void concurrentDuplicateWritesHaveExactlyOneClaimAndDispatch() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("one-write", "发瞬间");
        profile(f);
        var calls = new AtomicInteger();
        when(f.moments.publishCli(anyString(), anyString(), anyList(), any()))
                .thenAnswer(call -> Mono.fromCallable(() -> {
                    calls.incrementAndGet();
                    var args = (List<String>) call.getArgument(2);
                    String name = args.stream()
                            .filter(a -> a.startsWith("--name="))
                            .findFirst()
                            .orElseThrow()
                            .substring(7);
                    return json.readTree(
                            "{\"metadata\":{\"name\":\"" + name + "\"},\"spec\":{\"visible\":\"PUBLIC\"}}");
                }));
        Flux.range(0, 2)
                .flatMap(i -> f.confirmed(command, "moment_create", Map.of("content", "正文"))
                        .subscribeOn(Schedulers.parallel())
                        .onErrorReturn(Map.of("blocked", true)))
                .collectList()
                .block();
        assertThat(calls).hasValue(1);
        f.confirmed(command, "moment_create", Map.of("content", "正文")).block();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> f.confirmed(command, "moment_create", Map.of("content", "另一份正文"))
                        .block())
                .hasMessage("AI_WRITE_ALREADY_CLAIMED");
    }

    @Test
    void aLostWriteResponseIsNeverRetried() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("lost", "发瞬间");
        profile(f);
        when(f.moments.publishCli(anyString(), anyString(), anyList(), any()))
                .thenReturn(Mono.error(new IllegalStateException("CLI_TIMEOUT")));
        assertThatThrownBy(() -> f.confirmed(command, "moment_create", Map.of("content", "正文"))
                        .block())
                .hasMessage("CLI_TIMEOUT");
        assertThatThrownBy(() -> f.confirmed(command, "moment_create", Map.of("content", "正文"))
                        .block())
                .hasMessage("AI_WRITE_ALREADY_CLAIMED");
        verify(f.moments, times(1)).publishCli(eq("private-pat"), anyString(), anyList(), any());
    }

    @Test
    void fixedArgumentsRejectOptionsFilesAndDangerousTools() {
        for (var args : List.of(
                Map.<String, Object>of("name", "--help"),
                Map.<String, Object>of("name", "../../private"),
                Map.<String, Object>of("name", "post", "url", "http://other")))
            assertThatThrownBy(() -> HaloCliPolicy.invocation("post_get", args, "cmd"))
                    .hasMessage("AI_TOOL_INPUT_INVALID");
        assertThatThrownBy(() -> HaloCliPolicy.invocation("post_delete", Map.of("name", "post"), "cmd"))
                .hasMessage("AI_TOOL_INPUT_INVALID");
        assertThatThrownBy(() -> HaloCliPolicy.invocation("post_list", Map.of("size", 10000), "cmd"))
                .hasMessage("AI_TOOL_INPUT_INVALID");
        var draft = HaloCliPolicy.invocation(
                "post_create_draft", Map.of("title", "--publish=true", "content", "$(touch file)"), "cmd");
        assertThat(draft.arguments())
                .contains(
                        "--publish=false",
                        "--slug=" + draft.name(),
                        "--title=--publish=true",
                        "--content=$(touch file)");
    }

    @Test
    void draftCreationAcceptsServerNameOnlyWhenSlugMatchesAndItRemainsUnpublished() throws Exception {
        var draft = HaloCliPolicy.invocation("post_create_draft", Map.of("title", "标题", "content", "正文"), "cmd");
        var response = json.createObjectNode();
        response.putObject("metadata").put("name", "server-generated-name");
        var spec = response.putObject("spec").put("slug", draft.name()).put("publish", false);
        var result = (Map<?, ?>) HaloCliPolicy.result(draft, response);
        assertThat(result.get("name")).isEqualTo("server-generated-name");
        spec.put("slug", "another-request");
        assertThatThrownBy(() -> HaloCliPolicy.result(draft, response)).hasMessage("CLI_RESULT_UNKNOWN");
        spec.put("slug", draft.name()).put("publish", true);
        assertThatThrownBy(() -> HaloCliPolicy.result(draft, response)).hasMessage("CLI_RESULT_UNKNOWN");
    }

    @Test
    void draftLedgerPreservesTheActualServerAssignedName() throws Exception {
        var f = new AssistantFixture();
        profile(f);
        var command = f.command("post-create", "保存草稿");
        when(f.cli.run(argThat(args -> args.getFirst().equals("post")))).thenAnswer(call -> {
            List<String> args = call.getArgument(0);
            String slug = args.stream()
                    .filter(a -> a.startsWith("--slug="))
                    .findFirst()
                    .orElseThrow()
                    .substring(7);
            var response = json.createObjectNode();
            response.putObject("metadata").put("name", "assigned-by-halo");
            response.putObject("spec").put("slug", slug).put("publish", false);
            return Mono.just(response);
        });
        f.confirmed(command, "post_create_draft", Map.of("title", "标题", "content", "正文"))
                .block();
        var data = f.secrets.read(command.getSpec().getBodyRef()).block();
        assertThat(data.get("mutationName")).isEqualTo("assigned-by-halo");
        assertThat(data).doesNotContainKey("mutationLookup");
        f.confirmed(command, "post_create_draft", Map.of("title", "标题", "content", "正文"))
                .block();
        verify(f.cli, times(1)).run(argThat(args -> args.getFirst().equals("post")));
    }

    @Test
    void toolsOnlyReturnWhitelistedContentAndMetadata() throws Exception {
        var read = HaloCliPolicy.invocation("post_get", Map.of("name", "post"), "cmd");
        var result = HaloCliPolicy.result(
                read,
                json.readTree(
                        "{\"post\":{\"metadata\":{\"name\":\"post\",\"annotations\":{\"token\":\"private\"}},\"spec\":{\"title\":\"标题\",\"owner\":\"email@example.com\"}},\"content\":{\"raw\":\"正文\"}}"));
        assertThat(json.writeValueAsString(result))
                .contains("正文", "标题")
                .doesNotContain("private", "email@example.com", "annotations");
    }
}
