package com.xhhao.clawbotnotify.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.Ref;

class CommentDiscoveryTest {
    private final ExtensionFixture fixture = new ExtensionFixture();
    private final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    private final CommentDiscovery discovery = new CommentDiscovery(
            new BindingRepository(fixture.client), fixture.client, links, new OutboundQueue(fixture.client));

    private WechatBinding binding(long generation) {
        var binding = new WechatBinding();
        var meta = new Metadata();
        meta.setName("primary");
        binding.setMetadata(meta);
        var spec = new WechatBinding.Spec();
        spec.setGeneration(generation);
        spec.setEnabled(true);
        spec.setOwnerWechatUserId("owner");
        spec.setNotificationStartAt(Instant.parse("2026-11-13T00:00:00Z"));
        binding.setSpec(spec);
        return fixture.put(binding, fixture.get(WechatBinding.class, "primary") == null);
    }

    private Comment comment(String name, Instant created) {
        var comment = new Comment();
        var meta = new Metadata();
        meta.setName(name);
        meta.setCreationTimestamp(created);
        comment.setMetadata(meta);
        var spec = new Comment.CommentSpec();
        var ref = new Ref();
        ref.setGroup("content.halo.run");
        ref.setKind("Post");
        ref.setName("post");
        spec.setSubjectRef(ref);
        spec.setRaw("visitor body");
        spec.setContent("<p>visitor body</p>");
        comment.setSpec(spec);
        return fixture.put(comment, true);
    }

    private long tasks() {
        return fixture.client
                .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(1000))
                .block()
                .getTotal();
    }

    @Test
    void repeatedControllerEventsAndRestartProduceOneTaskPerGeneration() {
        binding(1);
        comment("a", Instant.now());
        discovery.observe("a").block();
        discovery.observe("a").block();
        discovery.tick(() -> true).block();
        assertThat(tasks()).isEqualTo(1);
        String code = links.forComment(1, "a").block().getSpec().getShortCode();
        assertThat(links.forCode(2, code).block()).isNull();
        var updated = fixture.get(WechatBinding.class, "primary");
        updated.getSpec().setGeneration(2);
        fixture.put(updated, false);
        discovery.observe("a").block();
        assertThat(tasks()).isEqualTo(2);
        assertThat(links.forComment(1, "a").block().getSpec().getShortCode()).isEqualTo(code);
    }

    @Test
    void ignoresHistoryUnsupportedSubjectsAndDisabledBindings() {
        var b = binding(1);
        comment("old", b.getSpec().getNotificationStartAt().minusSeconds(1));
        var unsupported = comment("other", Instant.now());
        unsupported.getSpec().getSubjectRef().setGroup("other");
        fixture.put(unsupported, false);
        discovery.observe("old").block();
        discovery.observe("other").block();
        b.getSpec().setEnabled(false);
        fixture.put(b, false);
        comment("disabled", Instant.now());
        discovery.observe("disabled").block();
        assertThat(tasks()).isZero();
    }

    @Test
    void rotatesBoundedPagesAndFindsDeletedSourcesAfterDowntime() {
        binding(1);
        for (int i = 0; i < 105; i++)
            comment("comment-" + i, Instant.parse("2026-11-13T01:00:00Z").plusSeconds(i));
        discovery.tick(() -> true).block();
        assertThat(tasks()).isEqualTo(100);
        discovery.tick(() -> true).block();
        assertThat(tasks()).isEqualTo(105);
        var deleted = fixture.get(Comment.class, "comment-0");
        fixture.client.delete(deleted).block();
        discovery.tick(() -> true).block();
        discovery.tick(() -> true).block();
        assertThat(links.forComment(1, "comment-0").block().getSpec().getState())
                .isEqualTo(CommentLink.State.DELETED);
    }

    @Test
    void collisionRetriesAndDeletionDoesNotRecycleCode() {
        binding(1);
        var sequence = new AtomicInteger();
        var controlled = new CommentLinkRepository(
                fixture.client, () -> sequence.getAndIncrement() < 2 ? "C-AAAAAA" : "C-BBBBBB");
        controlled.ensure(1, comment("a", Instant.now())).block();
        controlled.ensure(1, comment("b", Instant.now())).block();
        assertThat(controlled.forComment(1, "b").block().getSpec().getShortCode())
                .isEqualTo("C-BBBBBB");
        controlled.deleted(controlled.forComment(1, "a").block()).block();
        assertThat(controlled.forCode(1, "C-AAAAAA").block().getSpec().getState())
                .isEqualTo(CommentLink.State.DELETED);
    }
}
