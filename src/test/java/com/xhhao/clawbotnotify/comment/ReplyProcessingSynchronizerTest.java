package com.xhhao.clawbotnotify.comment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.*;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.Metadata;

class ReplyProcessingSynchronizerTest {
    final ExtensionFixture fixture = new ExtensionFixture();
    final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    final CredentialStore secrets = mock(CredentialStore.class);
    final HaloCommentReader reader = mock(HaloCommentReader.class);
    final RoleService roles = mock(RoleService.class);
    final WechatBinding binding = new WechatBinding();
    final Comment comment = new Comment();

    ReplyProcessingSynchronizerTest() {
        var meta = new Metadata();
        meta.setName("primary");
        binding.setMetadata(meta);
        var spec = new WechatBinding.Spec();
        binding.setSpec(spec);
        spec.setGeneration(3);
        spec.setEnabled(true);
        spec.setNotificationStartAt(Instant.now().minusSeconds(100));
        spec.setCredentialRef("credentials");
        fixture.put(binding, true);
        var cm = new Metadata();
        cm.setName("comment-a");
        cm.setCreationTimestamp(Instant.now());
        comment.setMetadata(cm);
        comment.setSpec(new Comment.CommentSpec());
        links.ensure(3, comment).block();
        when(secrets.read("credentials")).thenReturn(Mono.just(Map.of("haloPat", "owner-pat")));
        when(reader.parent(any(), eq("owner-pat"), eq("comment-a"))).thenReturn(Mono.just(comment));
        when(reader.reply(any(), eq("owner-pat"), eq("comment-a"), anyString()))
                .thenAnswer(call -> Mono.justOrEmpty(fixture.get(Reply.class, call.getArgument(3))));
        when(roles.getRolesByUsername(anyString())).thenReturn(Flux.just("super-role"));
        when(roles.contains(any(), any())).thenReturn(Mono.just(false));
        var user = new User();
        var um = new Metadata();
        um.setName("admin");
        user.setMetadata(um);
        user.setSpec(new User.UserSpec());
        fixture.put(user, true);
    }

    ReplyProcessingSynchronizer sync() {
        return new ReplyProcessingSynchronizer(
                new BindingRepository(fixture.client),
                fixture.client,
                links,
                secrets,
                reader,
                new CommentAuthors(fixture.client, roles));
    }

    Reply reply(String name, String ownerKind, String owner, boolean approved) {
        var reply = new Reply();
        var m = new Metadata();
        m.setName(name);
        m.setCreationTimestamp(Instant.now());
        reply.setMetadata(m);
        var spec = new Reply.ReplySpec();
        reply.setSpec(spec);
        spec.setCommentName("comment-a");
        spec.setApproved(approved);
        var author = new Comment.CommentOwner();
        author.setKind(ownerKind);
        author.setName(owner);
        spec.setOwner(author);
        return fixture.put(reply, true);
    }

    CommentLink.State state() {
        return links.forComment(3, "comment-a").block().getSpec().getState();
    }

    @Test
    void approvedAdministratorReplyClosesPendingOnlyOnceWithRealPatRead() {
        reply("r", User.KIND, "admin", true);
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.ANSWERED);
        sync().observe("r").block();
        verify(reader, times(1)).reply(any(), eq("owner-pat"), eq("comment-a"), eq("r"));
    }

    @Test
    void visitorUnapprovedDisabledAndOrdinaryAuthorsDoNotClosePending() {
        reply("visitor", "Email", "admin", true);
        reply("pending", User.KIND, "admin", false);
        sync().observe("visitor").block();
        sync().observe("pending").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        verifyNoInteractions(reader);
        reply("ordinary", User.KIND, "admin", true);
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("role-template-view-comments"));
        sync().observe("ordinary").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        var user = fixture.get(User.class, "admin");
        user.getSpec().setDisabled(true);
        fixture.put(user, false);
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("super-role"));
        sync().observe("ordinary").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
    }

    @Test
    void delegatedCommentManagerAlsoCountsAsAdministrator() {
        reply("moderator", User.KIND, "admin", true);
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("moderators"));
        when(roles.contains(java.util.List.of("moderators"), java.util.Set.of("role-template-manage-comments")))
                .thenReturn(Mono.just(true));
        sync().observe("moderator").block();
        assertThat(state()).isEqualTo(CommentLink.State.ANSWERED);
    }

    @Test
    void internalHintCannotBypassPatOrDeletedReply() {
        reply("r", User.KIND, "admin", true);
        when(reader.reply(any(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("HALO_READ_FORBIDDEN")));
        assertThatThrownBy(() -> sync().observe("r").block()).hasMessage("HALO_READ_FORBIDDEN");
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        when(reader.reply(any(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
    }

    @Test
    void currentPatReplyIsRecheckedAndManualIgnoreIsPreserved() {
        reply("r", User.KIND, "admin", true);
        var unapproved = fixture.get(Reply.class, "r");
        unapproved.getSpec().setApproved(false);
        when(reader.reply(any(), anyString(), anyString(), anyString())).thenReturn(Mono.just(unapproved));
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        links.changeState(3, "comment-a", CommentLink.State.IGNORED, false).block();
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.IGNORED);
    }

    @Test
    void generationChangeDuringReadCannotCloseOldOrNewBinding() {
        reply("r", User.KIND, "admin", true);
        when(reader.reply(any(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            var current = fixture.get(WechatBinding.class, "primary");
            current.getSpec().setGeneration(4);
            fixture.put(current, false);
            return Mono.just(fixture.get(Reply.class, "r"));
        });
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
    }

    @Test
    void boundedScanRepairsRepliesMissedDuringDowntime() {
        for (int i = 0; i < 100; i++) reply("visitor-" + i, "Email", "guest", true);
        var admin = reply("admin-last", User.KIND, "admin", true);
        var sync = sync();
        sync.tick(() -> true).block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        sync.tick(() -> true).block();
        assertThat(state()).isEqualTo(CommentLink.State.ANSWERED);
    }

    @Test
    void disabledBindingAndCancelledScanDoNotReadOrUpdate() {
        reply("r", User.KIND, "admin", true);
        sync().tick(() -> false).block();
        verifyNoInteractions(reader);
        var current = fixture.get(WechatBinding.class, "primary");
        current.getSpec().setEnabled(false);
        fixture.put(current, false);
        sync().observe("r").block();
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
        verifyNoInteractions(reader);
    }

    @Test
    void quotedAdministratorReplyClosesOnlyTheSelectedNestedTarget() {
        var first = reply("visitor-first", "Email", "guest", true);
        var second = reply("visitor-second", "Email", "guest", true);
        links.ensureReply(3, comment, first).block();
        links.ensureReply(3, comment, second).block();
        var answer = reply("answer", User.KIND, "admin", true);
        answer.getSpec().setQuoteReply("visitor-first");
        fixture.put(answer, false);
        sync().observe("answer").block();
        assertThat(links.forTarget(3, "comment-a", "visitor-first")
                        .block()
                        .getSpec()
                        .getState())
                .isEqualTo(CommentLink.State.ANSWERED);
        assertThat(links.forTarget(3, "comment-a", "visitor-second")
                        .block()
                        .getSpec()
                        .getState())
                .isEqualTo(CommentLink.State.OPEN);
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
    }

    @Test
    void changedQuoteInPatResponseCannotCloseTheHintedTarget() {
        var visitor = reply("visitor", "Email", "guest", true);
        links.ensureReply(3, comment, visitor).block();
        var answer = reply("answer", User.KIND, "admin", true);
        answer.getSpec().setQuoteReply("visitor");
        fixture.put(answer, false);
        var changed = fixture.get(Reply.class, "answer");
        changed.getSpec().setQuoteReply("other");
        when(reader.reply(any(), anyString(), anyString(), eq("answer"))).thenReturn(Mono.just(changed));
        sync().observe("answer").block();
        assertThat(links.forTarget(3, "comment-a", "visitor").block().getSpec().getState())
                .isEqualTo(CommentLink.State.OPEN);
        assertThat(state()).isEqualTo(CommentLink.State.OPEN);
    }
}
