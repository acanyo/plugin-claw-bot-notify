package com.xhhao.clawbotnotify.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.channel.WechatProtocolClient;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Ref;

class CommentDeliveryTest {
    @Test
    void progressIsSentOnlyWhileExecutingAndNeverAfterCompletion() {
        var command = new InboundCommand();
        var metadata = new Metadata();
        metadata.setName("slow-ai");
        command.setMetadata(metadata);
        var spec = new InboundCommand.Spec();
        spec.setGeneration(1);
        spec.setType(InboundCommand.Type.AI);
        spec.setWriteState(InboundCommand.WriteState.EXECUTING);
        command.setSpec(spec);
        fixture.put(command, true);
        var task = queue.enqueue(binding, "AI_PROGRESS", "slow-ai", item -> item.setCommandName("slow-ai"))
                .block();
        worker.tick(() -> true).block();
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(1);
        assertThat(sent.get()).contains("正在处理", "确认");
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.ACCEPTED);
        command = fixture.get(InboundCommand.class, "slow-ai");
        command.getSpec().setWriteState(InboundCommand.WriteState.SUCCEEDED);
        fixture.put(command, false);
        var late = queue.enqueue(binding, "AI_PROGRESS", "late", item -> item.setCommandName("slow-ai"))
                .block();
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(1);
        assertThat(fixture.get(OutboundTask.class, late.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.CANCELLED);
    }

    private final ExtensionFixture fixture = new ExtensionFixture();
    private final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    private final HaloCommentReader reader = mock(HaloCommentReader.class);
    private final WechatProtocolClient channel = mock(WechatProtocolClient.class);
    private final SecretCredentialStore credentials = new SecretCredentialStore(
            fixture.client, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
    private final com.xhhao.clawbotnotify.comment.CommentAuthors authors =
            mock(com.xhhao.clawbotnotify.comment.CommentAuthors.class);
    private final MessageComposer composer = new MessageComposer(fixture.client, links, reader, authors, credentials);
    private final OutboundDeliveryWorker worker = new OutboundDeliveryWorker(
            new BindingRepository(fixture.client), fixture.client, credentials, channel, composer);
    private final OutboundQueue queue = new OutboundQueue(fixture.client);
    private final AtomicReference<String> sent = new AtomicReference<>();
    private final AtomicInteger sends = new AtomicInteger();
    private WechatBinding binding;
    private Comment comment;

    @BeforeEach
    void setup() {
        binding = new WechatBinding();
        var meta = new Metadata();
        meta.setName("primary");
        binding.setMetadata(meta);
        var spec = new WechatBinding.Spec();
        spec.setGeneration(1);
        spec.setEnabled(true);
        spec.setCredentialRef("credentials");
        spec.setOwnerWechatUserId("owner");
        spec.setApiBaseUrl("https://ilinkai.weixin.qq.com");
        binding.setSpec(spec);
        fixture.put(binding, true);
        credentials
                .create("credentials", Map.of("haloPat", "read-pat", "botToken", "bot-token", "context", "context"))
                .block();
        comment = new Comment();
        var cm = new Metadata();
        cm.setName("comment");
        cm.setCreationTimestamp(Instant.now());
        comment.setMetadata(cm);
        var cs = new Comment.CommentSpec();
        cs.setRaw("正文".repeat(200));
        cs.setContent("<p>" + cs.getRaw() + "</p>");
        cs.setApproved(false);
        cs.setHidden(true);
        cs.setIpAddress("192.0.2.123");
        var owner = new Comment.CommentOwner();
        owner.setKind("Email");
        owner.setName("private@example.com");
        owner.setDisplayName("访客甲");
        cs.setOwner(owner);
        var ref = new Ref();
        ref.setGroup("content.halo.run");
        ref.setKind("Post");
        ref.setName("post");
        cs.setSubjectRef(ref);
        comment.setSpec(cs);
        when(reader.get(any(), anyString(), anyString())).thenReturn(Mono.just(comment));
        when(reader.title(any(), anyString())).thenReturn(Mono.just("测试文章"));
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    sends.incrementAndGet();
                    sent.set(call.getArgument(5));
                    return Mono.just("accepted-id");
                });
    }

    private OutboundTask notification() {
        var link = links.ensure(1, comment).block();
        return queue.enqueue(binding, "COMMENT", "comment", spec -> {
                    spec.setCommentName("comment");
                    spec.setShortCode(link.getSpec().getShortCode());
                })
                .block();
    }

    @Test
    void rendersCurrentContentAndFlagsWithoutPersistingBodyOrLeakingOwnerMetadata() {
        var task = notification();
        worker.tick(() -> true).block();
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.ACCEPTED);
        assertThat(sent.get())
                .contains("**新评论**", "测试文章", "访客甲", "待审核 · 已隐藏", "…", "引用")
                .doesNotContain("private@example.com", "192.0.2.123");
        assertThat(sent.get()).doesNotContain("正文".repeat(151));
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(1);
    }

    @Test
    void deletedBeforeDispatchCancelsNotificationAndKeepsTombstone() {
        var task = notification();
        when(reader.get(any(), anyString(), anyString())).thenReturn(Mono.empty());
        worker.tick(() -> true).block();
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.CANCELLED);
        assertThat(links.forComment(1, "comment").block().getSpec().getState()).isEqualTo(CommentLink.State.DELETED);
        assertThat(sends).hasValue(0);
    }

    @Test
    void permissionFailureKeepsNotificationUnsentWithBackoffThenRecovers() {
        var task = notification();
        when(reader.get(any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("HALO_READ_FORBIDDEN")));
        worker.tick(() -> true).block();
        worker.tick(() -> true).block();
        var saved = fixture.get(OutboundTask.class, task.getMetadata().getName());
        assertThat(saved.getSpec().getState()).isEqualTo(OutboundTask.State.PENDING);
        assertThat(saved.getSpec().getErrorCode()).isEqualTo("HALO_COMMENT_READ_FAILED");
        assertThat(sends).hasValue(0);
        saved.getSpec().setNextAttemptAt(Instant.now().minusSeconds(1));
        fixture.put(saved, false);
        when(reader.get(any(), anyString(), anyString())).thenReturn(Mono.just(comment));
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(1);
    }

    @Test
    void viewDeletedCodeProducesExplicitReceiptAndOtherGenerationCannotResolveIt() {
        var link = links.ensure(1, comment).block();
        links.deleted(link).block();
        var command = new InboundCommand();
        var meta = new Metadata();
        meta.setName("command");
        command.setMetadata(meta);
        var spec = new InboundCommand.Spec();
        spec.setGeneration(1);
        spec.setType(InboundCommand.Type.VIEW);
        spec.setShortCode(link.getSpec().getShortCode());
        command.setSpec(spec);
        fixture.put(command, true);
        var task = queue.enqueue(binding, "COMMAND", "command", t -> t.setCommandName("command"))
                .block();
        assertThat(composer.compose(binding, task, Map.of("haloPat", "pat")).block())
                .contains("评论已删除");
        binding.getSpec().setGeneration(2);
        spec.setGeneration(2);
        fixture.put(fixture.get(InboundCommand.class, "command"), false);
        var current = fixture.get(InboundCommand.class, "command");
        current.getSpec().setGeneration(2);
        fixture.put(current, false);
        assertThat(composer.compose(binding, task, Map.of("haloPat", "pat")).block())
                .contains("没有这个评论编号");
    }

    @Test
    void unicodeTruncationDoesNotSplitSurrogatePairs() {
        assertThat(MessageComposer.plain("😀".repeat(4), 3)).isEqualTo("😀😀😀…");
    }

    @Test
    void explicitRejectionWaitsForNewOwnerContextWithoutBlindlyRetrying() {
        var task = notification();
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new com.xhhao.clawbotnotify.channel.ChannelException(
                        com.xhhao.clawbotnotify.channel.ChannelException.Code.BUSINESS_REJECTED)));
        worker.tick(() -> true).block();
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.WAITING_CONTEXT);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("new-id"));
        worker.tick(() -> true).block();
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.WAITING_CONTEXT);
        credentials
                .update("credentials", values -> {
                    values.put("context", "fresh-context");
                    values.put("contextTimestamp", "2");
                })
                .block();
        worker.tick(() -> true).block();
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.ACCEPTED);
    }

    @Test
    void pendingPagesContainAtMostTenCurrentGenerationComments() {
        when(reader.probe(any(), anyString())).thenReturn(Mono.empty());
        for (int i = 0; i < 12; i++) {
            comment.getMetadata().setName("comment-" + i);
            links.ensure(1, comment).block();
        }
        var command = new InboundCommand();
        var meta = new Metadata();
        meta.setName("pending");
        command.setMetadata(meta);
        var spec = new InboundCommand.Spec();
        spec.setGeneration(1);
        spec.setType(InboundCommand.Type.PENDING);
        command.setSpec(spec);
        fixture.put(command, true);
        var task = queue.enqueue(binding, "COMMAND", "pending", t -> t.setCommandName("pending"))
                .block();
        String first = composer.compose(binding, task, Map.of("haloPat", "pat")).block();
        assertThat(first.lines().filter(line -> line.startsWith("C-")).count()).isEqualTo(10);
        assertThat(first).contains("下一页：待处理 2");
        var second = fixture.get(InboundCommand.class, "pending");
        second.getSpec().setPage(2);
        fixture.put(second, false);
        assertThat(composer.compose(binding, task, Map.of("haloPat", "pat"))
                        .block()
                        .lines()
                        .filter(line -> line.startsWith("C-"))
                        .count())
                .isEqualTo(2);
    }

    private OutboundTask nestedNotification() {
        binding = fixture.get(WechatBinding.class, "primary");
        binding.getSpec().setNotificationStartAt(Instant.now().minusSeconds(100));
        binding = fixture.put(binding, false);
        var child = new run.halo.app.core.extension.content.Reply();
        var m = new Metadata();
        m.setName("child");
        m.setCreationTimestamp(Instant.now());
        child.setMetadata(m);
        var s = new run.halo.app.core.extension.content.Reply.ReplySpec();
        child.setSpec(s);
        s.setCommentName("comment");
        s.setRaw("楼中楼新追问");
        s.setContent("<p>楼中楼新追问</p>");
        s.setQuoteReply("previous");
        s.setOwner(comment.getSpec().getOwner());
        var previous = new run.halo.app.core.extension.content.Reply();
        previous.setSpec(new run.halo.app.core.extension.content.Reply.ReplySpec());
        previous.getSpec().setRaw("此前的答复");
        when(reader.parent(any(), anyString(), anyString())).thenReturn(Mono.just(comment));
        when(reader.reply(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("child")))
                .thenReturn(Mono.just(child));
        when(reader.reply(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("previous")))
                .thenReturn(Mono.just(previous));
        when(authors.moderator(any())).thenReturn(Mono.just(false));
        var link = links.ensureReply(1, comment, child).block();
        return queue.enqueue(binding, "REPLY", "child", spec -> {
                    spec.setCommentName("comment");
                    spec.setTargetReplyName("child");
                    spec.setShortCode(link.getSpec().getShortCode());
                })
                .block();
    }

    @Test
    void nestedNotificationIncludesTargetAndAuthorizedContext() {
        var task = nestedNotification();
        worker.tick(() -> true).block();
        assertThat(sent.get())
                .contains(
                        "新楼中楼回复",
                        "楼中楼新追问",
                        "原评论",
                        "所回复的内容",
                        "此前的答复",
                        task.getSpec().getShortCode())
                .doesNotContain("private@example.com", "192.0.2.123");
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getTargetReplyName())
                .isEqualTo("child");
    }

    @Test
    void deletedNestedSourceCancelsWithoutNotifyingParentInstead() {
        var task = nestedNotification();
        when(reader.reply(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("child")))
                .thenReturn(Mono.empty());
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(0);
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.CANCELLED);
    }

    @Test
    void moderatorReplyIsSuppressedAgainAtDispatch() {
        var task = nestedNotification();
        when(authors.moderator(any())).thenReturn(Mono.just(true));
        worker.tick(() -> true).block();
        assertThat(sends).hasValue(0);
        assertThat(links.forTarget(1, "comment", "child").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
        assertThat(fixture.get(OutboundTask.class, task.getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(OutboundTask.State.CANCELLED);
    }
}
