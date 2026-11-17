package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.command.*;
import com.xhhao.clawbotnotify.comment.CommentAuthors;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.delivery.*;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;

class AssistantRoutingTest {
    @Test
    void clearCommandActuallyResetsHistoryWithoutCallingTheModel() throws Exception {
        var f = new AssistantFixture();
        f.history.messages(f.binding, f.config).block();
        f.history
                .append(
                        f.binding,
                        f.config,
                        "previous",
                        "旧问题",
                        java.util.List.of(run.halo.aifoundation.message.ModelMessage.assistant("旧答复")))
                .block();
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        var clear = message("clear", "/clear");
        router.capture(f.binding, clear).block();
        router.capture(f.binding, clear).block();
        assertThat(f.history.messages(f.binding, f.config).block()).isEmpty();
        assertThat(commands(f)).hasSize(1);
        assertThat(commands(f).getFirst().getSpec().getType()).isEqualTo(InboundCommand.Type.AI_RESET);
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void aProgressNoticeCannotBeQuotedToConfirmAFuturePreview() throws Exception {
        var f = new AssistantFixture();
        f.command("progress-command", "发瞬间");
        var task = new OutboundQueue(f.resources.client)
                .enqueue(f.binding, "AI_PROGRESS", "progress-command", spec -> spec.setCommandName("progress-command"))
                .block();
        task.getSpec().setState(OutboundTask.State.ACCEPTED);
        task.getSpec().setUpstreamMessageId("progress-id");
        f.resources.put(task, false);
        var inbound = message("bad-confirm", "确认发布");
        ((ObjectNode) inbound.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "progress-id");
        assertThat(new QuotedCommentResolver(f.resources.client)
                        .resolve(f.binding, inbound)
                        .block())
                .isNull();
        verifyNoInteractions(f.model, f.cli);
    }

    final ObjectMapper json =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    ObjectNode message(String id, String body) {
        var message = json.createObjectNode();
        message.put("message_id", id)
                .put("from_user_id", "owner")
                .put("message_type", 1)
                .put("message_state", 2)
                .put("create_time_ms", Instant.now().toEpochMilli());
        message.putArray("item_list")
                .addObject()
                .put("type", 1)
                .putObject("text_item")
                .put("text", body);
        return message;
    }

    InboundCommandService router(AssistantFixture f, QuotedCommentResolver quotes) {
        var replies = mock(CommentReplyExecutor.class);
        when(replies.process(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
        return new InboundCommandService(
                f.resources.client,
                new InstanceIdentity(f.resources.client),
                new OutboundQueue(f.resources.client),
                quotes,
                f.secrets,
                replies,
                mock(CommentActionExecutor.class),
                f.settings,
                f.service,
                f.confirmations,
                new com.xhhao.clawbotnotify.assistant.review.AssistantPreviewReader(
                        f.resources.client, f.secrets, f.drafts, f.authority),
                com.xhhao.clawbotnotify.support.CommandServices.noMedia());
    }

    java.util.List<InboundCommand> commands(AssistantFixture f) {
        return f.resources
                .client
                .listBy(InboundCommand.class, new ListOptions(), PageRequestImpl.ofSize(100))
                .block()
                .getItems();
    }

    @Test
    void ownerTextGeneratesEncryptedResponseAndOneReceiptWithReadableConsoleDetail() throws Exception {
        var f = new AssistantFixture();
        when(f.model.generate(any(), any()))
                .thenReturn(
                        Mono.just(GenerateTextResult.builder().text("私密的AI答复").build()));
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        var message = message("18446744073709551615", "私密的用户问题");
        router.capture(f.binding, message).block();
        router.capture(f.binding, message).block();
        var rows = commands(f);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSpec().getType()).isEqualTo(InboundCommand.Type.AI);
        assertThat(rows.getFirst().getSpec().getState()).isEqualTo(InboundCommand.State.QUEUED);
        assertThat(json.writeValueAsString(rows)).doesNotContain("私密的用户问题", "私密的AI答复");
        var tasks = f.resources
                .client
                .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(100))
                .block();
        assertThat(tasks.getTotal()).isEqualTo(1);
        var links = new CommentLinkRepository(f.resources.client);
        var reader = mock(HaloCommentReader.class);
        var composer = new MessageComposer(f.resources.client, links, reader, mock(CommentAuthors.class), f.secrets);
        assertThat(composer.compose(f.binding, tasks.getItems().getFirst(), Map.of())
                        .block())
                .isEqualTo("私密的AI答复");
        var bindings = mock(BindingService.class);
        when(bindings.owned("admin")).thenReturn(Mono.just(f.binding));
        var records = new CommandRecordService(f.resources.client, bindings, links, f.secrets, reader);
        var detail =
                records.detail("admin", rows.getFirst().getMetadata().getName()).block();
        assertThat(detail.submittedMarkdown()).isEqualTo("私密的用户问题");
        assertThat(detail.replyMarkdown()).isEqualTo("私密的AI答复");
        f.binding.getSpec().setGeneration(4);
        var historical =
                records.detail("admin", rows.getFirst().getMetadata().getName()).block();
        assertThat(historical.replyMarkdown()).isNull();
        assertThat(historical.contentErrorCode()).isEqualTo("HISTORICAL_GENERATION");
        verify(f.model, times(1)).generate(any(), any());
    }

    @Test
    void aPendingAiReceiptRecoversEvenIfTheAssistantIsDisabledAfterCreation() throws Exception {
        var f = new AssistantFixture();
        when(f.model.generate(any(), any()))
                .thenReturn(Mono.just(GenerateTextResult.builder().text("已保存答复").build()));
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        var message = message("pending-receipt", "帮我写文案");
        f.resources.failCreatesFor = OutboundTask.class;
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> router.capture(f.binding, message).block());
        assertThat(commands(f)).hasSize(1);
        f.config.setEnabled(false);
        f.resources.failCreatesFor = null;
        router.capture(f.binding, message).block();
        assertThat(commands(f).getFirst().getSpec().getState()).isEqualTo(InboundCommand.State.QUEUED);
        verify(f.model, times(1)).generate(any(), any());
    }

    @Test
    void disabledAssistantIgnoresOrdinaryTextButExplicitAiExplainsDisabledState() throws Exception {
        var f = new AssistantFixture();
        f.config.setEnabled(false);
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        router.capture(f.binding, message("ordinary", "你好")).block();
        assertThat(commands(f)).isEmpty();
        router.capture(f.binding, message("explicit", "AI 你好")).block();
        assertThat(commands(f).getFirst().getSpec().getErrorCode()).isEqualTo("AI_DISABLED");
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void explicitCommandsAndQuotedCommentsKeepTheirOriginalRoutingWhenAiIsEnabled() throws Exception {
        var f = new AssistantFixture();
        var quotes = mock(QuotedCommentResolver.class);
        when(quotes.resolve(any(), any()))
                .thenReturn(Mono.just(new QuotedCommentResolver.Target("root", "child", null, "quote")));
        var router = router(f, quotes);
        router.capture(f.binding, message("help", "帮助")).block();
        var quote = message("comment", "这是评论回复");
        ((ObjectNode) quote.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "quote");
        router.capture(f.binding, quote).block();
        assertThat(commands(f))
                .extracting(c -> c.getSpec().getType())
                .containsExactlyInAnyOrder(InboundCommand.Type.HELP, InboundCommand.Type.REPLY);
        var reply = commands(f).stream()
                .filter(c -> c.getSpec().getType() == InboundCommand.Type.REPLY)
                .findFirst()
                .orElseThrow();
        assertThat(reply.getSpec().getTargetReplyName()).isEqualTo("child");
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void foreignSendersCannotUseAiAndSettingsFailureIsReportedWithoutGeneration() throws Exception {
        var f = new AssistantFixture();
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        var foreign = message("foreign", "AI 发瞬间");
        foreign.put("from_user_id", "foreign");
        router.capture(f.binding, foreign).block();
        assertThat(commands(f)).isEmpty();
        when(f.settings.get()).thenReturn(Mono.error(new IllegalStateException("AI_SETTINGS_INVALID")));
        router.capture(f.binding, message("settings", "你好")).block();
        assertThat(commands(f).getFirst().getSpec().getErrorCode()).isEqualTo("AI_SETTINGS_INVALID");
        verifyNoInteractions(f.model, f.cli);
    }

    @Test
    void aQuotedAiPreviewWithoutACommentRoutesToConfirmationAndCannotReachTheModel() throws Exception {
        var f = new AssistantFixture();
        var source = f.command("preview", "发瞬间");
        f.drafts
                .stage(source, "moment_create", Map.of("content", "真实预览正文"), null, false)
                .block();
        var task = new OutboundQueue(f.resources.client)
                .enqueue(f.binding, "COMMAND", "preview", spec -> spec.setCommandName("preview"))
                .block();
        task.getSpec().setState(OutboundTask.State.ACCEPTED);
        task.getSpec().setUpstreamMessageId("preview-message");
        f.resources.put(task, false);
        when(f.cli.run(java.util.List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.moments.publishCli(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            java.util.List<String> args = call.getArgument(2);
            String name = args.stream()
                    .filter(arg -> arg.startsWith("--name="))
                    .findFirst()
                    .orElseThrow()
                    .substring(7);
            var result = json.createObjectNode();
            result.putObject("metadata").put("name", name);
            result.putObject("spec").put("visible", "PUBLIC");
            return Mono.just(result);
        });
        var inbound = message("confirmation", "确认发布");
        ((ObjectNode) inbound.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "preview-message");
        var router = router(f, new QuotedCommentResolver(f.resources.client));
        router.capture(f.binding, inbound).block();
        router.capture(f.binding, inbound).block();
        var confirmation = commands(f).stream()
                .filter(command -> command.getSpec().getType() == InboundCommand.Type.AI_CONFIRM)
                .findFirst()
                .orElseThrow();
        assertThat(confirmation.getSpec().getState()).isEqualTo(InboundCommand.State.QUEUED);
        assertThat(confirmation.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        verify(f.moments, times(1)).publishCli(eq("private-pat"), anyString(), anyList(), any());
        verifyNoInteractions(f.model);
    }

    @Test
    void quotedAiCommentRequestKeepsTheExactCommentTarget() throws Exception {
        var f = new AssistantFixture();
        var quotes = mock(QuotedCommentResolver.class);
        when(quotes.resolve(any(), any()))
                .thenReturn(Mono.just(new QuotedCommentResolver.Target("root", "child", null, "quoted")));
        // Capture routing without an external provider or website.
        var assistant = mock(AiCommandService.class);
        when(assistant.process(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
        var router = new InboundCommandService(
                f.resources.client,
                new InstanceIdentity(f.resources.client),
                new OutboundQueue(f.resources.client),
                quotes,
                f.secrets,
                f.replies,
                mock(CommentActionExecutor.class),
                f.settings,
                assistant,
                f.confirmations,
                new com.xhhao.clawbotnotify.assistant.review.AssistantPreviewReader(
                        f.resources.client, f.secrets, f.drafts, f.authority),
                com.xhhao.clawbotnotify.support.CommandServices.noMedia());
        var inbound = message("quoted-ai", "AI 帮我礼貌回复");
        ((ObjectNode) inbound.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "quoted");
        router.capture(f.binding, inbound).block();
        var command = commands(f).getFirst();
        assertThat(command.getSpec().getType()).isEqualTo(InboundCommand.Type.AI);
        assertThat(command.getSpec().getCommentName()).isEqualTo("root");
        assertThat(command.getSpec().getTargetReplyName()).isEqualTo("child");
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).containsEntry("body", "帮我礼貌回复");
        verifyNoInteractions(f.halo, f.model, f.cli);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"确认发送", "确认执行", "确认全文保存 abcdef123456", "确认图片操作 abcdef123456", "查看第 2 段", "查看差异第 2 段"})
    void reservedConfirmationTextOnAnOrdinaryCommentNeverBecomesAReplyBody(String confirmation) throws Exception {
        var f = new AssistantFixture();
        var quotes = mock(QuotedCommentResolver.class);
        when(quotes.resolve(any(), any()))
                .thenReturn(Mono.just(new QuotedCommentResolver.Target("root", null, null, "quoted")));
        var router = router(f, quotes);
        var inbound = message("wrong-quote", confirmation);
        ((ObjectNode) inbound.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "quoted");
        router.capture(f.binding, inbound).block();
        assertThat(commands(f).getFirst().getSpec().getType()).isEqualTo(InboundCommand.Type.INVALID);
        verifyNoInteractions(f.halo, f.model, f.cli);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"查看第 2 段", "查看差异", "查看差异第 2 段"})
    void quotedArticleNavigationRoutesWithoutModelCallsOrConsumingPreview(String body) throws Exception {
        var f = new AssistantFixture();
        var source = f.command("article-preview", "长文章");
        f.drafts
                .stage(source, "post_create_draft", Map.of("title", "标题", "content", "长文\n".repeat(2000)), null, false)
                .block();
        var quotes = mock(QuotedCommentResolver.class);
        when(quotes.resolve(any(), any()))
                .thenReturn(Mono.just(new QuotedCommentResolver.Target(null, null, "article-preview", "quoted")));
        var inbound = message("read-part", body);
        ((ObjectNode) inbound.path("item_list").get(0)).putObject("ref_msg").put("svr_id", "quoted");
        router(f, quotes).capture(f.binding, inbound).block();
        var command = commands(f).stream()
                .filter(c -> !c.getMetadata().getName().equals("article-preview"))
                .findFirst()
                .orElseThrow();
        assertThat(command.getSpec().getType()).isEqualTo(InboundCommand.Type.AI_PREVIEW);
        assertThat(command.getSpec().getPage()).isEqualTo(body.equals("查看差异") ? 0 : body.contains("差异") ? -2 : 2);
        assertThat(command.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(f.secrets.read(source.getSpec().getBodyRef()).block()).doesNotContainKey("proposalClaim");
        verifyNoInteractions(f.model, f.cli, f.halo);
    }
}
