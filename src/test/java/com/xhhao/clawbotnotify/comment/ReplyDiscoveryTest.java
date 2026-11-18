package com.xhhao.clawbotnotify.comment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.*;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.*;

class ReplyDiscoveryTest {
    final ExtensionFixture fixture = new ExtensionFixture();
    final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    final CommentAuthors authors = mock(CommentAuthors.class);
    final WechatBinding binding = new WechatBinding();
    final Comment parent = new Comment();

    ReplyDiscoveryTest() {
        var bm = new Metadata();
        bm.setName("primary");
        binding.setMetadata(bm);
        var bs = new WechatBinding.Spec();
        binding.setSpec(bs);
        bs.setEnabled(true);
        bs.setGeneration(3);
        bs.setOwnerWechatUserId("owner");
        bs.setHaloUsername("admin");
        bs.setNotificationStartAt(Instant.now().minusSeconds(100));
        fixture.put(binding, true);
        var cm = new Metadata();
        cm.setName("old-root");
        cm.setCreationTimestamp(Instant.now().minusSeconds(10000));
        parent.setMetadata(cm);
        var cs = new Comment.CommentSpec();
        parent.setSpec(cs);
        cs.setSubjectRef(new Ref());
        cs.getSubjectRef().setGroup("content.halo.run");
        cs.getSubjectRef().setKind("Post");
        cs.getSubjectRef().setName("post");
        fixture.put(parent, true);
        when(authors.moderator(any())).thenReturn(Mono.just(false));
    }

    ReplyDiscovery discovery() {
        return new ReplyDiscovery(
                new BindingRepository(fixture.client),
                fixture.client,
                links,
                new OutboundQueue(fixture.client),
                authors);
    }

    Reply reply(String name) {
        var r = new Reply();
        var m = new Metadata();
        m.setName(name);
        m.setCreationTimestamp(Instant.now());
        r.setMetadata(m);
        var s = new Reply.ReplySpec();
        r.setSpec(s);
        s.setCommentName("old-root");
        s.setOwner(new Comment.CommentOwner());
        s.getOwner().setKind("Email");
        s.getOwner().setName("private@example.com");
        return fixture.put(r, true);
    }

    long taskCount() {
        return fixture.client
                .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(1000))
                .block()
                .getTotal();
    }

    @ParameterizedTest
    @CsvSource({"content.halo.run, Post", "moment.halo.run, Moment"})
    void newRepliesUnderOldParentsHaveIndependentCodesAndDoNotDuplicateAfterRestart(String group, String kind) {
        var storedParent = fixture.get(Comment.class, "old-root");
        storedParent.getSpec().getSubjectRef().setGroup(group);
        storedParent.getSpec().getSubjectRef().setKind(kind);
        fixture.put(storedParent, false);
        reply("a");
        reply("b");
        discovery().observe("a").block();
        discovery().observe("a").block();
        discovery().tick(() -> true).block();
        assertThat(taskCount()).isEqualTo(2);
        var a = links.forReply(3, "a").block();
        var b = links.forReply(3, "b").block();
        assertThat(a.getSpec().getShortCode()).isNotEqualTo(b.getSpec().getShortCode());
        assertThat(links.forComment(3, "old-root").block()).isNull();
        assertThat(links.forTarget(4, "old-root", "a").block()).isNull();
    }

    @Test
    void ignoresOldRepliesUnsupportedParentsOwnAndModeratorReplies() {
        var r = reply("old");
        r.getMetadata().setCreationTimestamp(Instant.now().minusSeconds(1000));
        fixture.put(r, false);
        discovery().observe("old").block();
        var own = reply("own");
        own.getSpec().getOwner().setKind("User");
        own.getSpec().getOwner().setName("admin");
        fixture.put(own, false);
        discovery().observe("own").block();
        reply("moderator");
        when(authors.moderator(any())).thenReturn(Mono.just(true));
        discovery().observe("moderator").block();
        when(authors.moderator(any())).thenReturn(Mono.just(false));
        var unsupported = fixture.get(Comment.class, "old-root");
        unsupported.getSpec().getSubjectRef().setKind("Other");
        fixture.put(unsupported, false);
        reply("unsupported");
        discovery().observe("unsupported").block();
        assertThat(taskCount()).isZero();
    }

    @Test
    void deletionLeavesATombstoneWithoutAffectingOtherReplies() {
        var a = reply("a");
        reply("b");
        discovery().tick(() -> true).block();
        fixture.client.delete(a).block();
        discovery().tick(() -> true).block();
        assertThat(links.forReply(3, "a").block().getSpec().getState()).isEqualTo(CommentLink.State.DELETED);
        assertThat(links.forReply(3, "b").block().getSpec().getState()).isEqualTo(CommentLink.State.OPEN);
        assertThat(taskCount()).isEqualTo(2);
    }

    @Test
    void boundedScanningFindsMoreThanOnePageAndHonorsCancellation() {
        for (int i = 0; i < 101; i++) reply("r-" + i);
        var discovery = discovery();
        discovery.tick(() -> false).block();
        assertThat(taskCount()).isZero();
        discovery.tick(() -> true).block();
        assertThat(taskCount()).isEqualTo(100);
        discovery.tick(() -> true).block();
        assertThat(taskCount()).isEqualTo(101);
    }

    @Test
    void deletingParentTombstonesChildAndDisabledBindingCreatesNothing() {
        reply("a");
        discovery().observe("a").block();
        fixture.client.delete(fixture.get(Comment.class, "old-root")).block();
        discovery().tick(() -> true).block();
        assertThat(links.forReply(3, "a").block().getSpec().getState()).isEqualTo(CommentLink.State.DELETED);
        var b = fixture.get(WechatBinding.class, "primary");
        b.getSpec().setEnabled(false);
        fixture.put(b, false);
        reply("b");
        discovery().observe("b").block();
        assertThat(taskCount()).isEqualTo(1);
    }
}
