package com.xhhao.clawbotnotify.command;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.halo.HaloWriteException;
import com.xhhao.clawbotnotify.model.*;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.*;

class QuotedReplyFlowTest {
    final ExtensionFixture fixture = new ExtensionFixture();
    final ObjectMapper json =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    final SecretCredentialStore store = new SecretCredentialStore(
            fixture.client, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
    final HaloCommentReader reader = mock(HaloCommentReader.class);
    final HaloIdentityVerifier halo = mock(HaloIdentityVerifier.class);
    final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    final WechatBinding binding = new WechatBinding();
    final Comment comment = new Comment();

    QuotedReplyFlowTest() {
        var spec = new WechatBinding.Spec();
        binding.setSpec(spec);
        spec.setEnabled(true);
        spec.setGeneration(3);
        spec.setBotId("bot");
        spec.setOwnerWechatUserId("owner");
        spec.setCredentialRef("credentials");
        store.create("credentials", Map.of("haloPat", "private-pat")).block();
        var meta = new Metadata();
        meta.setName("comment-a");
        comment.setMetadata(meta);
        var cs = new Comment.CommentSpec();
        cs.setApproved(true);
        comment.setSpec(cs);
        links.ensure(3, comment).block();
        when(reader.get(any(), eq("private-pat"), eq("comment-a")))
                .thenAnswer(call -> Mono.just(json.convertValue(comment, Comment.class)));
        when(halo.postReply(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> Mono.just(json.createObjectNode()
                                .set("metadata", json.createObjectNode().put("name", "reply-a")))
                        .map(node -> {
                            ((ObjectNode) node).putObject("spec").put("commentName", "comment-a");
                            return node;
                        }));
        receipt("notice", null, "新评论通知");
    }

    CommentReplyExecutor executor() {
        return new CommentReplyExecutor(fixture.client, store, reader, halo, links);
    }

    InboundCommandService service() {
        return new InboundCommandService(
                fixture.client,
                new InstanceIdentity(fixture.client),
                new OutboundQueue(fixture.client),
                new QuotedCommentResolver(fixture.client),
                store,
                executor(),
                new CommentActionExecutor(fixture.client, store, reader, links),
                com.xhhao.clawbotnotify.support.CommandServices.disabledSettings(),
                mock(com.xhhao.clawbotnotify.assistant.AiCommandService.class),
                mock(com.xhhao.clawbotnotify.assistant.draft.AssistantConfirmationExecutor.class),
                mock(com.xhhao.clawbotnotify.assistant.review.AssistantPreviewReader.class),
                com.xhhao.clawbotnotify.support.CommandServices.noMedia());
    }

    List<InboundCommand> commands() {
        return fixture.client
                .listBy(InboundCommand.class, new ListOptions(), PageRequestImpl.ofSize(100))
                .block()
                .getItems();
    }

    InboundCommand command(String type) {
        return commands().stream()
                .filter(c -> c.getSpec().getType().name().equals(type))
                .findFirst()
                .orElseThrow();
    }

    void receipt(String name, String command, String body) {
        var task = new OutboundTask();
        var meta = new Metadata();
        meta.setName(name);
        task.setMetadata(meta);
        var spec = new OutboundTask.Spec();
        task.setSpec(spec);
        spec.setGeneration(3);
        spec.setTargetUserId("owner");
        spec.setState(OutboundTask.State.ACCEPTED);
        spec.setCommentName("comment-a");
        spec.setCommandName(command);
        spec.setQuoteTextHash(QuotedCommentResolver.fingerprint(body));
        fixture.put(task, true);
    }

    ObjectNode message(String id, String quote, String body) {
        var message = json.createObjectNode();
        message.put("message_id", id);
        message.put("from_user_id", "owner");
        message.put("message_type", 1);
        message.put("message_state", 2);
        message.put("create_time_ms", Instant.now().toEpochMilli());
        var item = message.putArray("item_list").addObject();
        item.put("type", 1);
        item.putObject("text_item").put("text", body);
        item.putObject("ref_msg")
                .putObject("message_item")
                .putObject("text_item")
                .put("text", quote);
        return message;
    }

    @Test
    void quotedIgnoreAndHandledActionsChangeLocalStateWithoutPublishing() {
        var ignore = message("ignore", "新评论通知", "忽略");
        service().capture(binding, ignore).block();
        service().capture(binding, ignore).block();
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.IGNORED);
        assertThat(command("IGNORE").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        service().capture(binding, message("handled", "新评论通知", "标记已处理")).block();
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.ANSWERED);
        assertThat(command("MARK_HANDLED").getSpec().getBodyRef()).isNull();
        verifyNoInteractions(halo);
    }

    @Test
    void localActionRejectsDeletedSourceAndUnauthorizedPat() {
        when(reader.get(any(), anyString(), anyString())).thenReturn(Mono.empty());
        service().capture(binding, message("ignore-missing", "新评论通知", "忽略")).block();
        assertThat(command("IGNORE").getSpec().getErrorCode()).isEqualTo("COMMENT_DELETED");
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
        verifyNoInteractions(halo);
    }

    @Test
    void directMarkdownReplyIsEncryptedPublishedOnceAndMarksAnsweredAcrossRestart() throws Exception {
        var message = message("18446744073709551615", "新评论通知", "感谢 **支持**\n`safe()`");
        service().capture(binding, message).block();
        service().capture(binding, message).block();
        verify(halo, times(1))
                .postReply(
                        eq("private-pat"),
                        eq("comment-a"),
                        eq("感谢 **支持**\n`safe()`"),
                        argThat(html -> html.contains("<strong>支持</strong>") && html.contains("<code>safe()</code>")));
        var saved = command("REPLY");
        assertThat(saved.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(json.writeValueAsString(saved)).doesNotContain("感谢", "private-pat");
        assertThat(new String(
                        fixture.get(Secret.class, saved.getSpec().getBodyRef())
                                .getData()
                                .get("encrypted-v1"),
                        StandardCharsets.UTF_8))
                .doesNotContain("支持");
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.ANSWERED);
    }

    @Test
    void pendingCommentNeedsQuotedConfirmationAndRepeatedConfirmationCannotPublishAgain() {
        comment.getSpec().setApproved(false);
        service().capture(binding, message("draft", "新评论通知", "**待确认的正文**")).block();
        var draft = command("REPLY");
        assertThat(draft.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        verifyNoInteractions(halo);
        receipt("confirmation", draft.getMetadata().getName(), "审核确认提示");
        service().capture(binding, message("approve", "审核确认提示", "审核并回复")).block();
        service()
                .capture(binding, message("approve-again", "审核确认提示", "审核并回复 新的正文"))
                .block();
        verify(halo, times(1)).postReply(eq("private-pat"), eq("comment-a"), eq("**待确认的正文**"), anyString());
        assertThat(fixture.get(InboundCommand.class, draft.getMetadata().getName())
                        .getSpec()
                        .getWriteState())
                .isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(commands())
                .anySatisfy(c -> assertThat(c.getSpec().getErrorCode()).isEqualTo("DRAFT_ALREADY_CONFIRMED"));
    }

    @Test
    void successBeforeQueueFailureIsResumedWithoutASecondPost() {
        fixture.failCreatesFor = OutboundTask.class;
        var message = message("one", "新评论通知", "回复正文");
        assertThatThrownBy(() -> service().capture(binding, message).block()).hasMessage("SIMULATED_CREATE_FAILURE");
        assertThat(command("REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        fixture.failCreatesFor = null;
        service().capture(binding, message).block();
        verify(halo, times(1)).postReply(anyString(), anyString(), anyString(), anyString());
        assertThat(command("REPLY").getSpec().getState()).isEqualTo(InboundCommand.State.QUEUED);
    }

    @Test
    void failedAnsweredBookkeepingRecoversWithoutRepeatingSuccessfulReply() {
        doAnswer(call -> Mono.error(new IllegalStateException("LINK_UPDATE_FAILED")))
                .when(fixture.client)
                .update(isA(CommentLink.class));
        var message = message("bookkeeping", "新评论通知", "正文");
        assertThatThrownBy(() -> service().capture(binding, message).block()).hasMessage("LINK_UPDATE_FAILED");
        assertThat(command("REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        doAnswer(call -> Mono.fromCallable(() -> fixture.put((CommentLink) call.getArgument(0), false)))
                .when(fixture.client)
                .update(isA(CommentLink.class));
        service().capture(binding, message).block();
        verify(halo, times(1)).postReply(anyString(), anyString(), anyString(), anyString());
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.ANSWERED);
    }

    @Test
    void timeoutIsUnknownAndRecoveryNeverRetriesThePost() {
        when(halo.postReply(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new HaloWriteException("HALO_WRITE_UNKNOWN", false)));
        var message = message("unknown", "新评论通知", "不能重复");
        service().capture(binding, message).block();
        executor().recoverInterrupted().block();
        service().capture(binding, message).block();
        assertThat(command("REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.UNKNOWN);
        verify(halo, times(1)).postReply(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedClaimNeverSendsAndInterruptedClaimBecomesUnknown() {
        fixture.failUpdates = true;
        var message = message("claim", "新评论通知", "保存执行状态之后才发布");
        assertThatThrownBy(() -> service().capture(binding, message).block()).hasMessage("SIMULATED_STORAGE_FAILURE");
        verifyNoInteractions(halo);
        fixture.failUpdates = false;
        var record = command("REPLY");
        record.getSpec().setWriteState(InboundCommand.WriteState.EXECUTING);
        fixture.put(record, false);
        executor().recoverInterrupted().block();
        service().capture(binding, message).block();
        verifyNoInteractions(halo);
        assertThat(command("REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.UNKNOWN);
    }

    @Test
    void missingCommentForbiddenReadsAndExpiredMessagesNeverPost() {
        when(reader.get(any(), anyString(), anyString())).thenReturn(Mono.empty());
        service().capture(binding, message("deleted", "新评论通知", "正文")).block();
        when(reader.get(any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("HALO_READ_FORBIDDEN")));
        service().capture(binding, message("forbidden", "新评论通知", "正文")).block();
        var expired = message("expired", "新评论通知", "正文");
        expired.put("create_time_ms", Instant.now().minusSeconds(90000).toEpochMilli());
        service().capture(binding, expired).block();
        verifyNoInteractions(halo);
        assertThat(commands())
                .extracting(c -> c.getSpec().getErrorCode())
                .containsExactlyInAnyOrder("COMMENT_DELETED", "HALO_READ_FORBIDDEN", "COMMAND_EXPIRED");
    }

    @Test
    void unrecognizedQuoteAndForeignSenderNeverPost() {
        service().capture(binding, message("unresolved", "C-ABC123", "正文")).block();
        var foreign = message("foreign", "新评论通知", "正文");
        foreign.put("from_user_id", "someone-else");
        service().capture(binding, foreign).block();
        verifyNoInteractions(halo);
        assertThat(commands()).hasSize(1);
        assertThat(commands().getFirst().getSpec().getType()).isEqualTo(InboundCommand.Type.QUOTE_UNRESOLVED);
    }

    @Test
    void ambiguousApprovalResultReportsParentStateWithoutRepeating() {
        comment.getSpec().setApproved(false);
        when(halo.postReply(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            comment.getSpec().setApproved(true);
            return Mono.error(new HaloWriteException("HALO_WRITE_UNKNOWN", false));
        });
        service().capture(binding, message("approval", "新评论通知", "审核并回复 正文")).block();
        assertThat(command("APPROVE_REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.PARTIAL);
        service().capture(binding, message("approval", "新评论通知", "审核并回复 正文")).block();
        verify(halo, times(1)).postReply(anyString(), anyString(), anyString(), anyString());
    }

    run.halo.app.core.extension.content.Reply nested(String name, boolean approved) {
        binding.getSpec().setNotificationStartAt(Instant.now().minusSeconds(1000));
        var nested = new run.halo.app.core.extension.content.Reply();
        var meta = new Metadata();
        meta.setName(name);
        meta.setCreationTimestamp(Instant.now());
        nested.setMetadata(meta);
        var spec = new run.halo.app.core.extension.content.Reply.ReplySpec();
        nested.setSpec(spec);
        spec.setCommentName("comment-a");
        spec.setApproved(approved);
        spec.setRaw("继续追问");
        spec.setContent("<p>继续追问</p>");
        fixture.put(nested, true);
        links.ensureReply(3, comment, nested).block();
        when(reader.parent(any(), eq("private-pat"), eq("comment-a"))).thenReturn(Mono.just(comment));
        when(reader.reply(any(), eq("private-pat"), eq("comment-a"), eq(name)))
                .thenAnswer(
                        call -> Mono.justOrEmpty(fixture.get(run.halo.app.core.extension.content.Reply.class, name)));
        when(halo.postReply(anyString(), anyString(), eq(name), anyString(), anyString()))
                .thenAnswer(call -> {
                    var response = json.createObjectNode();
                    response.putObject("metadata").put("name", "nested-answer");
                    response.putObject("spec").put("commentName", "comment-a").put("quoteReply", name);
                    return Mono.just(response);
                });
        return nested;
    }

    void nestedReceipt(String id, String replyName, String commandId, String body) {
        receipt(id, commandId, body);
        var task = fixture.get(OutboundTask.class, id);
        task.getSpec().setTargetReplyName(replyName);
        fixture.put(task, false);
    }

    @Test
    void nestedReplyPostsExactQuoteOnceAndOnlyClosesThatTarget() {
        nested("child-a", true);
        nested("child-b", true);
        nestedReceipt("nested-notice", "child-a", null, "楼中楼甲");
        var msg = message("nested-write", "楼中楼甲", "给甲的回复");
        service().capture(binding, msg).block();
        service().capture(binding, msg).block();
        verify(halo, times(1)).postReply(eq("private-pat"), eq("comment-a"), eq("child-a"), eq("给甲的回复"), anyString());
        verify(halo, never()).postReply(anyString(), anyString(), anyString(), anyString());
        assertThat(command("REPLY").getSpec().getTargetReplyName()).isEqualTo("child-a");
        assertThat(command("REPLY").getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        assertThat(links.forTarget(3, "comment-a", "child-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.ANSWERED);
        assertThat(links.forTarget(3, "comment-a", "child-b").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
    }

    @Test
    void pendingNestedReplyRequiresConfirmationEvenWhenRootIsApproved() {
        nested("pending-child", false);
        nestedReceipt("pending-notice", "pending-child", null, "待审楼中楼");
        service().capture(binding, message("nested-draft", "待审楼中楼", "待审回复正文")).block();
        var draft = command("REPLY");
        assertThat(draft.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.AWAITING_APPROVAL);
        verifyNoInteractions(halo);
        nestedReceipt("nested-confirm", "pending-child", draft.getMetadata().getName(), "楼中楼确认提示");
        service()
                .capture(binding, message("nested-approve", "楼中楼确认提示", "审核并回复"))
                .block();
        service()
                .capture(binding, message("nested-approve-again", "楼中楼确认提示", "审核并回复"))
                .block();
        verify(halo, times(1))
                .postReply(eq("private-pat"), eq("comment-a"), eq("pending-child"), eq("待审回复正文"), anyString());
    }

    @Test
    void removedNestedReplyCannotFallBackToReplyingToItsParent() {
        var child = nested("deleted-child", true);
        nestedReceipt("deleted-notice", "deleted-child", null, "已删除楼中楼");
        fixture.client.delete(child).block();
        service()
                .capture(binding, message("deleted-child-command", "已删除楼中楼", "不要发到主评论"))
                .block();
        verifyNoInteractions(halo);
        assertThat(command("REPLY").getSpec().getErrorCode()).isEqualTo("COMMENT_DELETED");
    }

    @Test
    void nestedIgnoreDoesNotIgnoreSiblingOrRoot() {
        nested("ignore-child", true);
        nestedReceipt("ignore-notice", "ignore-child", null, "忽略楼中楼");
        service()
                .capture(binding, message("ignore-child-command", "忽略楼中楼", "忽略"))
                .block();
        assertThat(links.forTarget(3, "comment-a", "ignore-child")
                        .block()
                        .getSpec()
                        .getState())
                .isEqualTo(CommentLink.State.IGNORED);
        assertThat(links.forComment(3, "comment-a").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
        verifyNoInteractions(halo);
    }

    @Test
    void identicalQuoteTextFromDifferentRepliesIsAmbiguousWithinSameRoot() {
        nested("same-a", true);
        nested("same-b", true);
        nestedReceipt("same-notice-a", "same-a", null, "同样的通知正文");
        nestedReceipt("same-notice-b", "same-b", null, "同样的通知正文");
        service()
                .capture(binding, message("ambiguous-nested", "同样的通知正文", "无法选择"))
                .block();
        assertThat(command("QUOTE_UNRESOLVED")).isNotNull();
        verifyNoInteractions(halo);
    }
}
