package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class FullCliToolsTest {
    final ObjectMapper json = new ObjectMapper();

    AssistantFixture fixture() throws Exception {
        var f = new AssistantFixture();
        when(f.cli.profile()).thenReturn("clawbot");
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.cli.help(anyList())).thenAnswer(call -> {
            List<String> path = call.getArgument(0);
            String help =
                    switch (String.join(" ", path)) {
                        case "" ->
                            "Commands:\n  comment  Comments\n  auth  Auth\n  shop  Shop\n  moment  Moments\n  new-plugin  Future commands\n";
                        case "moment" -> "Commands:\n  create  Create moment\n";
                        case "moment create" ->
                            "Options:\n  --profile <name>  Profile\n  --json  JSON\n  --content <text>  Content\n  --visible <state>  Visibility\n";
                        case "comment" -> "Commands:\n  list  List comments\n  delete <name>  Delete comment\n";
                        case "auth" -> "Commands:\n  profile  Profiles\n  login  Login\n";
                        case "auth profile" -> "Commands:\n  list  List profiles\n";
                        case "auth login" ->
                            "Options:\n  --profile <name>  Profile\n  --url <url>  Site URL\n  --token <token>  Token\n  --json  JSON\n";
                        case "auth profile list" -> "Options:\n  --json  Output JSON\n  -h, --help  Help\n";
                        case "shop" -> "Commands:\n  product  Products\n";
                        case "shop product" -> "Commands:\n  create  Create product\n";
                        case "new-plugin" -> "Commands:\n  synchronize  Newly added command\n";
                        case "shop product create" ->
                            "Options:\n  --profile <name>  Profile\n  --json  JSON\n  --stdin  Read JSON\n";
                        default ->
                            "Options:\n  --profile <name>  Profile\n  --json  JSON\n  --size <number>  Page size\n  --sort <sort>  Sort\n  --force  Skip confirmation\n  -y, --yes  Skip prompt\n  -h, --help  Help\n";
                    };
            return Mono.just(help);
        });
        when(f.cli.invoke(anyList(), nullable(String.class)))
                .thenReturn(Mono.just(new HaloCliProcess.Result(0, json.readTree("{\"ok\":true}"), "")));
        return f;
    }

    InboundCommand confirm(AssistantFixture f, InboundCommand source, String id, String text) {
        var c = f.command(id, text);
        c.getSpec().setType(InboundCommand.Type.AI_CONFIRM);
        c.getSpec().setPreviewOf(source.getMetadata().getName());
        return f.resources.put(c, false);
    }

    @Test
    void fullCliMomentCreationUsesTheSameBotPublisherAfterConfirmation() throws Exception {
        var f = fixture();
        f.config.setAllowWrites(true);
        var source = f.command("bot-cli-preview", "CLI 发瞬间");
        f.tools
                .execute(
                        f.binding,
                        source,
                        "halo_cli",
                        Map.of("arguments", List.of("moment", "create", "--content=准确正文", "--visible=PRIVATE")))
                .block();
        var draft = f.drafts.load(f.binding, source.getMetadata().getName()).block();
        assertThat(f.drafts.preview(draft)).contains("拾光 Bot（clawbot）", "准确正文", "确认执行");
        verifyNoInteractions(f.moments);
        verify(f.cli, never()).invoke(anyList(), any());
        when(f.moments.publishCli(eq("private-pat"), anyString(), anyList(), any()))
                .thenReturn(
                        Mono.just(
                                json.readTree(
                                        "{\"metadata\":{\"name\":\"moment\"},\"spec\":{\"owner\":\"clawbot\",\"visible\":\"PRIVATE\"}}")));
        var command = confirm(f, source, "bot-cli-confirm", "确认执行");
        var done = f.confirmations.process(f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("clawbot");
        f.confirmations.process(f.binding, done).block();
        verify(f.moments, times(1)).publishCli(eq("private-pat"), eq("bot-cli-confirm"), anyList(), any());
        verify(f.cli, never()).invoke(anyList(), any());
    }

    @Test
    void commentsReturnDatesTotalsAndModerationWithoutLeakingSecrets() throws Exception {
        var f = fixture();
        var c = f.command("comments", "本周评论");
        when(f.cli.invoke(anyList(), isNull()))
                .thenReturn(Mono.just(new HaloCliProcess.Result(
                        0,
                        json.readTree(
                                "{\"total\":42,\"items\":[{\"metadata\":{\"creationTimestamp\":\"2026-11-17T03:00:00Z\",\"annotations\":{\"token\":\"secret-annotation\"}},\"spec\":{\"approved\":false,\"content\":\"hello\",\"email\":\"private@example.com\"}}],\"accessToken\":\"private-access\"}"),
                        "")));
        Object result = f.tools
                .execute(f.binding, c, "halo_cli", Map.of("arguments", List.of("comment", "list", "--size=100")))
                .block();
        assertThat(HaloCliOutput.pretty(result))
                .contains("42", "2026-11-17T03:00:00Z", "approved", "hello")
                .doesNotContain("private-access", "secret-annotation", "private@example.com");
        assertThat(f.secrets.read(c.getSpec().getBodyRef()).block()).doesNotContainKeys("proposal", "mutation");
        verify(f.cli).invoke(List.of("comment", "list", "--size=100", "--profile=clawbot", "--json"), null);
    }

    @Test
    void deleteOnlyStagesAndAccurateConfirmationDispatchesOnce() throws Exception {
        var f = fixture();
        var source = f.command("preview-delete", "删除评论");
        f.tools
                .execute(
                        f.binding, source, "halo_cli", Map.of("arguments", List.of("comment", "delete", "comment-123")))
                .block();
        verify(f.cli, never()).invoke(anyList(), any());
        var proposal = f.drafts.decode(
                f.secrets.read(source.getSpec().getBodyRef()).block().get("proposal"));
        assertThat(f.drafts.preview(proposal)).contains("确认执行", "comment-123", "--force");
        assertThat(f.confirmations
                        .process(f.binding, confirm(f, source, "wrong", "确认发布"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("AI_CONFIRMATION_MISMATCH");
        var done = f.confirmations
                .process(f.binding, confirm(f, source, "right", "确认执行"))
                .block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        f.confirmations.process(f.binding, done).block();
        assertThat(f.confirmations
                        .process(f.binding, confirm(f, source, "again", "确认执行"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("AI_PREVIEW_ALREADY_USED");
        verify(f.cli, times(1)).invoke(anyList(), isNull());
        verifyNoInteractions(f.model);
    }

    @Test
    void stdinIsKeptInEncryptedProposalAndOnlySentAfterConfirmation() throws Exception {
        var f = fixture();
        String payload = "{\"title\":\"" + "正文".repeat(2500) + "\",\"password\":\"private-password\"}";
        var source = f.command("large-product", "创建商品");
        f.tools
                .execute(
                        f.binding,
                        source,
                        "halo_cli",
                        Map.of("arguments", List.of("shop", "product", "create", "--stdin"), "stdin", payload))
                .block();
        var draft = f.drafts.decode(
                f.secrets.read(source.getSpec().getBodyRef()).block().get("proposal"));
        assertThat(draft.input().get("stdin")).isEqualTo(payload);
        assertThat(f.drafts.preview(draft)).contains("完整参数", "确认执行").doesNotContain("private-password");
        verify(f.cli, never()).invoke(anyList(), any());
        f.confirmations
                .process(f.binding, confirm(f, source, "stdin-confirm", "确认执行"))
                .block();
        verify(f.cli).invoke(List.of("shop", "product", "create", "--stdin", "--profile=clawbot", "--json"), payload);
    }

    @Test
    void nativeFailureAfterDispatchIsUnknownAndNeverRetried() throws Exception {
        var f = fixture();
        when(f.cli.invoke(anyList(), any()))
                .thenReturn(Mono.just(new HaloCliProcess.Result(1, "", "server unavailable")));
        var source = f.command("unknown-preview", "删除评论");
        f.tools
                .execute(f.binding, source, "halo_cli", Map.of("arguments", List.of("comment", "delete", "comment-1")))
                .block();
        var done = f.confirmations
                .process(f.binding, confirm(f, source, "unknown-confirm", "确认执行"))
                .block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.UNKNOWN);
        f.confirmations.process(f.binding, done).block();
        f.tools.executeConfirmed(f.binding, done).onErrorComplete().block();
        verify(f.cli, times(1)).invoke(anyList(), any());
    }

    @Test
    void writeSettingAndSiteAreRecheckedBeforeNativeDispatch() throws Exception {
        var f = fixture();
        var source = f.command("setting-preview", "删除评论");
        f.tools
                .execute(f.binding, source, "halo_cli", Map.of("arguments", List.of("comment", "delete", "x")))
                .block();
        f.config.setAllowWrites(false);
        assertThat(f.confirmations
                        .process(f.binding, confirm(f, source, "disabled-confirm", "确认执行"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("AI_WRITES_DISABLED");
        f.config.setAllowWrites(true);
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"https://other.example\"}")));
        assertThat(f.confirmations
                        .process(f.binding, confirm(f, source, "site-confirm", "确认执行"))
                        .block()
                        .getSpec()
                        .getErrorCode())
                .isEqualTo("CLI_SITE_MISMATCH");
        verify(f.cli, never()).invoke(anyList(), any());
    }

    @Test
    void newCommandsAndNestedGroupsWorkWithoutAnApplicationWhitelist() throws Exception {
        var f = fixture();
        var catalog = new HaloCliCatalog(f.cli);
        assertThat(catalog.resolve(Map.of("arguments", List.of("new-plugin", "synchronize")))
                        .block()
                        .write())
                .isTrue();
        var profiles = catalog.resolve(Map.of("arguments", List.of("auth", "profile", "list")))
                .block();
        assertThat(profiles.write()).isFalse();
        assertThat(profiles.arguments()).containsExactly("auth", "profile", "list", "--json");
        assertThat(catalog.help(List.of("shop", "product", "create")).block()).contains("--stdin");
        assertThatThrownBy(() -> catalog.help(List.of("shop", "--delete")).block())
                .hasMessage("CLI_COMMAND_INVALID");
        assertThatThrownBy(() -> catalog.resolve(Map.of("arguments", List.of("comment", "list", "--profile=foreign")))
                        .block())
                .hasMessage("CLI_PROFILE_MISMATCH");
        assertThatThrownBy(() -> catalog.resolve(Map.of("arguments", List.of("comment", "list", "--invented")))
                        .block())
                .hasMessage("CLI_OPTION_INVALID");
    }

    @Test
    void concurrentGenericConfirmationsUseTheSameSingleDispatchClaim() throws Exception {
        var f = fixture();
        var source = f.command("parallel-source", "删除评论");
        f.tools
                .execute(f.binding, source, "halo_cli", Map.of("arguments", List.of("comment", "delete", "x")))
                .block();
        var commands = List.of(confirm(f, source, "parallel-one", "确认执行"), confirm(f, source, "parallel-two", "确认执行"));
        var done = reactor.core.publisher.Flux.fromIterable(commands)
                .flatMap(c ->
                        f.confirmations.process(f.binding, c).subscribeOn(reactor.core.scheduler.Schedulers.parallel()))
                .collectList()
                .block();
        assertThat(done)
                .extracting(c -> c.getSpec().getWriteState())
                .containsExactlyInAnyOrder(InboundCommand.WriteState.SUCCEEDED, InboundCommand.WriteState.REJECTED);
        verify(f.cli, times(1)).invoke(anyList(), any());
    }

    @Test
    void consoleDetailsIncludeAllLongParametersAfterCredentialRedaction() throws Exception {
        var f = fixture();
        String id = "in-" + com.xhhao.clawbotnotify.support.StableIds.hash("full-parameters");
        var source = f.command(id, "创建商品");
        String payload = "{\"title\":\"" + "准确正文".repeat(1000) + "尾部标记\",\"password\":\"private-password\"}";
        f.tools
                .execute(
                        f.binding,
                        source,
                        "halo_cli",
                        Map.of("arguments", List.of("shop", "product", "create", "--stdin"), "stdin", payload))
                .block();
        f.secrets
                .update(source.getSpec().getBodyRef(), values -> values.put("aiResponse", "微信摘要"))
                .block();
        var bindingService = mock(com.xhhao.clawbotnotify.binding.BindingService.class);
        when(bindingService.owned("admin")).thenReturn(Mono.just(f.binding));
        var records = new com.xhhao.clawbotnotify.command.CommandRecordService(
                f.resources.client,
                bindingService,
                new com.xhhao.clawbotnotify.comment.CommentLinkRepository(f.resources.client),
                f.secrets,
                f.comments);
        var detail = records.detail("admin", id).block();
        assertThat(detail.replyMarkdown()).contains("完整 CLI 参数", "尾部标记").doesNotContain("private-password");
        assertThat(detail.replyMarkdown().length()).isGreaterThan(4000);
        f.binding.getSpec().setGeneration(4);
        assertThat(records.detail("admin", id).block().replyMarkdown()).isNull();
    }

    @Test
    void argumentSeparatorCannotDisableProfileAndJsonFlags() throws Exception {
        var f = fixture();
        var invocation = new HaloCliCatalog(f.cli)
                .resolve(Map.of("arguments", List.of("comment", "delete", "--", "--profile=foreign")))
                .block();
        assertThat(invocation.arguments().indexOf("--profile=clawbot"))
                .isLessThan(invocation.arguments().indexOf("--"));
        assertThat(invocation.arguments().indexOf("--force"))
                .isLessThan(invocation.arguments().indexOf("--"));
    }

    @Test
    void changedNativeContractCannotExecuteAnOldPreview() throws Exception {
        var f = fixture();
        var source = f.command("old-contract", "删除评论");
        f.tools
                .execute(f.binding, source, "halo_cli", Map.of("arguments", List.of("comment", "delete", "x")))
                .block();
        when(f.cli.help(List.of("comment", "delete")))
                .thenReturn(Mono.just(
                        "Options:\n  --json  JSON\n  --profile <name>  Profile\n  --force  Force\n  --yes  Yes\n"));
        var done = f.confirmations
                .process(f.binding, confirm(f, source, "contract-confirm", "确认执行"))
                .block();
        assertThat(done.getSpec().getErrorCode()).isEqualTo("CLI_CONTRACT_CHANGED");
        verify(f.cli, never()).invoke(anyList(), any());
    }

    @Test
    void authDiscoveryAndProfileReadsWorkWithoutAWorkingBusinessProfileButLoginIsSameSite() throws Exception {
        var f = fixture();
        when(f.cli.run(anyList())).thenReturn(Mono.error(new IllegalStateException("CLI_FAILED")));
        var c = f.command("auth-local", "查询配置");
        f.tools
                .execute(f.binding, c, "halo_cli", Map.of("arguments", List.of("auth", "profile", "list")))
                .block();
        verify(f.cli).invoke(List.of("auth", "profile", "list", "--json"), null);
        assertThatThrownBy(() -> f.tools
                        .execute(
                                f.binding,
                                c,
                                "halo_cli",
                                Map.of(
                                        "arguments",
                                        List.of("auth", "login", "--url=https://other.example", "--token=private")))
                        .block())
                .hasMessage("CLI_SITE_MISMATCH");
        assertThatThrownBy(() -> f.tools
                        .execute(
                                f.binding,
                                c,
                                "halo_cli",
                                Map.of(
                                        "arguments",
                                        List.of(
                                                "auth",
                                                "login",
                                                "--url=http://localhost:18091",
                                                "--url=https://other.example")))
                        .block())
                .hasMessage("CLI_OPTION_INVALID");
        verify(f.cli, never()).run(anyList());
    }

    @Test
    void cliRevisionInvalidatesOldPreviewAndPreservesUnchangedStdin() throws Exception {
        var f = fixture();
        var source = f.command("revision-source", "创建商品");
        String stdin = "{\"title\":\"准确正文\",\"password\":\"private-password\"}";
        f.tools
                .execute(
                        f.binding,
                        source,
                        "halo_cli",
                        Map.of("arguments", List.of("shop", "product", "create", "--stdin"), "stdin", stdin))
                .block();
        when(f.model.generate(any(), any())).thenAnswer(call -> {
            run.halo.aifoundation.chat.GenerateTextRequest request = call.getArgument(1);
            assertThat(request.getSystem()).doesNotContain("private-password");
            assertThat(request.getTools()).extracting(tool -> tool.getName()).containsExactly("preview_revise");
            return request.getTools()
                    .getFirst()
                    .getExecutor()
                    .execute(run.halo.aifoundation.tool.ToolExecutionContext.builder()
                            .toolName("preview_revise")
                            .toolCallId("revision")
                            .input(Map.of("arguments", List.of("shop", "product", "create", "--stdin")))
                            .build())
                    .map(result -> run.halo.aifoundation.chat.GenerateTextResult.builder()
                            .text("已生成新预览")
                            .build());
        });
        var revision = f.command("revision-new", "保留原正文");
        revision.getSpec().setPreviewOf(source.getMetadata().getName());
        revision = f.resources.put(revision, false);
        var done = f.service.process(f.binding, revision).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        assertThat(f.drafts.load(f.binding, "revision-new").block().input()).containsEntry("stdin", stdin);
        assertThatThrownBy(() -> f.drafts.load(f.binding, "revision-source").block())
                .hasMessage("AI_PREVIEW_ALREADY_USED");
        f.confirmations
                .process(f.binding, confirm(f, done, "revision-confirm", "确认执行"))
                .block();
        verify(f.cli).invoke(List.of("shop", "product", "create", "--stdin", "--profile=clawbot", "--json"), stdin);
    }

    @Test
    void secretArgumentsAndTextAreMaskedWhileExactInputIsNotModified() {
        var input = Map.<String, Object>of(
                "arguments",
                List.of("auth", "login", "--password", "private-password", "--token=private-token"),
                "stdin",
                "{\"apiKey\":\"private-key\",\"title\":\"preserved\"}");
        assertThat(HaloCliOutput.pretty(HaloCliOutput.proposal(input)))
                .contains("preserved")
                .doesNotContain("private-password", "private-token", "private-key");
        assertThat(HaloCliOutput.text("Authorization: Bearer abc123 token=xyz pat_private"))
                .doesNotContain("abc123", "xyz", "pat_private");
        assertThat(input.get("arguments").toString()).contains("private-password");
    }
}
