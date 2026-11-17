package com.xhhao.clawbotnotify.command;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.model.*;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Metadata;

class CommandRecordServiceTest {
    final ExtensionFixture fixture = new ExtensionFixture();
    final BindingService bindings = mock(BindingService.class);
    final CredentialStore secrets = mock(CredentialStore.class);
    final HaloCommentReader reader = mock(HaloCommentReader.class);
    final CommentLinkRepository links = new CommentLinkRepository(fixture.client);
    final CommandRecordService service = new CommandRecordService(fixture.client, bindings, links, secrets, reader);
    final WechatBinding binding = new WechatBinding();
    final Comment comment = new Comment();

    CommandRecordServiceTest() {
        var bs = new WechatBinding.Spec();
        binding.setSpec(bs);
        bs.setEnabled(true);
        bs.setGeneration(3);
        bs.setCredentialRef("private-credentials");
        when(bindings.owned("admin")).thenReturn(Mono.just(binding));
        when(bindings.owned("other-admin"))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "BINDING_OWNER_REQUIRED")));
        var meta = new Metadata();
        meta.setName("comment-a");
        comment.setMetadata(meta);
        comment.setSpec(new Comment.CommentSpec());
        comment.getSpec().setRaw("source comment");
        links.ensure(3, comment).block();
        when(secrets.read("private-body-ref")).thenReturn(Mono.just(Map.of("body", "**private input**")));
        when(secrets.read("private-credentials")).thenReturn(Mono.just(Map.of("haloPat", "private-pat")));
        when(reader.get(any(), eq("private-pat"), eq("comment-a"))).thenReturn(Mono.just(comment));
        var reply = new Reply();
        reply.setSpec(new Reply.ReplySpec());
        reply.getSpec().setRaw("website reply");
        reply.getSpec().setCommentName("comment-a");
        when(reader.reply(any(), eq("private-pat"), eq("comment-a"), eq("reply-a")))
                .thenReturn(Mono.just(reply));
    }

    InboundCommand record(String key, InboundCommand.Type type, InboundCommand.WriteState state) {
        var record = new InboundCommand();
        var meta = new Metadata();
        meta.setName("in-" + StableIds.hash(key));
        record.setMetadata(meta);
        var spec = new InboundCommand.Spec();
        record.setSpec(spec);
        spec.setType(type);
        spec.setGeneration(3);
        spec.setReceivedAt(Instant.now());
        spec.setWriteState(state);
        spec.setCommentName("comment-a");
        spec.setBodyRef("private-body-ref");
        spec.setReplyName("reply-a");
        return fixture.put(record, true);
    }

    @Test
    void websiteSuccessAndUnknownReceiptRemainIndependentAndListDoesNotReadSecrets() throws Exception {
        var command = record("one", InboundCommand.Type.REPLY, InboundCommand.WriteState.SUCCEEDED);
        var task = new OutboundTask();
        var meta = new Metadata();
        meta.setName("out-"
                + StableIds.hash(
                        "primary", "3", "COMMAND", command.getMetadata().getName()));
        task.setMetadata(meta);
        task.setSpec(new OutboundTask.Spec());
        task.getSpec().setState(OutboundTask.State.UNKNOWN);
        fixture.put(task, true);
        var page = service.list(1, 20, null, null).block();
        var row = page.items().getFirst();
        assertThat(row.writeState()).isEqualTo("SUCCEEDED");
        assertThat(row.receiptState()).isEqualTo("UNKNOWN");
        assertThat(row.reviewRequired()).isFalse();
        assertThat(row.shortCode()).startsWith("C-");
        assertThat(row.commentName()).isEqualTo("comment-a");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertThat(json).doesNotContain("private-body-ref", "private-pat", "private input");
        verifyNoInteractions(secrets, reader);
    }

    @Test
    void filtersReviewResultsAndPaginatesWithoutDroppingTotal() {
        for (int i = 0; i < 23; i++)
            record(
                    "row-" + i,
                    InboundCommand.Type.REPLY,
                    i % 2 == 0 ? InboundCommand.WriteState.UNKNOWN : InboundCommand.WriteState.SUCCEEDED);
        record("help", InboundCommand.Type.HELP, null);
        var page = service.list(2, 10, "REPLY", null).block();
        assertThat(page.total()).isEqualTo(23);
        assertThat(page.items()).hasSize(10);
        var review = service.list(1, 20, null, "NEEDS_REVIEW").block();
        assertThat(review.total()).isEqualTo(12);
        assertThat(review.items()).allMatch(row -> row.reviewRequired());
        assertThatThrownBy(() -> service.list(1, 101, null, null).block()).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(1, 20, "fake", null).block()).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(1, 20, null, "bad").block()).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void detailLoadsEncryptedSubmissionAndActualReplyOnlyForBindingOwner() {
        var command = record("one", InboundCommand.Type.REPLY, InboundCommand.WriteState.SUCCEEDED);
        var detail = service.detail("admin", command.getMetadata().getName()).block();
        assertThat(detail.submittedMarkdown()).isEqualTo("**private input**");
        assertThat(detail.replyMarkdown()).isEqualTo("website reply");
        assertThat(detail.commentMarkdown()).isEqualTo("source comment");
        assertThat(detail.contentErrorCode()).isNull();
        assertThatThrownBy(() -> service.detail(
                                "other-admin", command.getMetadata().getName())
                        .block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void historicalGenerationNeverDecryptsOrUsesCurrentCredentialsForOldContent() {
        var command = record("old", InboundCommand.Type.REPLY, InboundCommand.WriteState.SUCCEEDED);
        binding.getSpec().setGeneration(4);
        var detail = service.detail("admin", command.getMetadata().getName()).block();
        assertThat(detail.contentErrorCode()).isEqualTo("HISTORICAL_GENERATION");
        assertThat(detail.submittedMarkdown()).isNull();
        verifyNoInteractions(secrets, reader);
    }

    @Test
    void missingPatPermissionKeepsRecordAndSubmissionWithoutLeakingInternalErrors() {
        var command = record("forbidden", InboundCommand.Type.REPLY, InboundCommand.WriteState.UNKNOWN);
        when(reader.get(any(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("unsafe-private-pat")));
        var detail = service.detail("admin", command.getMetadata().getName()).block();
        assertThat(detail.record().reviewRequired()).isTrue();
        assertThat(detail.submittedMarkdown()).isEqualTo("**private input**");
        assertThat(detail.contentErrorCode()).isEqualTo("HALO_CONTENT_UNAVAILABLE");
        assertThat(detail.commentMarkdown()).isNull();
    }

    @Test
    void missingRecordAndMissingReplyHaveDifferentOutcomes() {
        assertThatThrownBy(() -> service.detail("admin", "not-a-record").block())
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        var command = record("deleted", InboundCommand.Type.REPLY, InboundCommand.WriteState.SUCCEEDED);
        when(reader.reply(any(), anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        var detail = service.detail("admin", command.getMetadata().getName()).block();
        assertThat(detail.record().writeState()).isEqualTo("SUCCEEDED");
        assertThat(detail.contentErrorCode()).isEqualTo("REPLY_DELETED");
    }

    @Test
    void viewByNestedCodeAndReplyRecordsShowTargetAndPublishedReplySeparately() {
        binding.getSpec().setNotificationStartAt(Instant.now().minusSeconds(100));
        var nested = new Reply();
        var meta = new Metadata();
        meta.setName("nested");
        meta.setCreationTimestamp(Instant.now());
        nested.setMetadata(meta);
        nested.setSpec(new Reply.ReplySpec());
        nested.getSpec().setCommentName("comment-a");
        nested.getSpec().setRaw("visitor follow-up");
        var link = links.ensureReply(3, comment, nested).block();
        when(reader.parent(any(), eq("private-pat"), eq("comment-a"))).thenReturn(Mono.just(comment));
        when(reader.reply(any(), eq("private-pat"), eq("comment-a"), eq("nested")))
                .thenReturn(Mono.just(nested));
        var view = record("view-nested", InboundCommand.Type.VIEW, null);
        view.getSpec().setCommentName(null);
        view.getSpec().setShortCode(link.getSpec().getShortCode());
        view.getSpec().setReplyName(null);
        fixture.put(view, false);
        var detail = service.detail("admin", view.getMetadata().getName()).block();
        assertThat(detail.record().targetReplyName()).isEqualTo("nested");
        assertThat(detail.commentMarkdown()).isEqualTo("source comment");
        assertThat(detail.targetReplyMarkdown()).isEqualTo("visitor follow-up");
        assertThat(detail.replyMarkdown()).isNull();
        var written = record("write-nested", InboundCommand.Type.REPLY, InboundCommand.WriteState.SUCCEEDED);
        written.getSpec().setTargetReplyName("nested");
        fixture.put(written, false);
        detail = service.detail("admin", written.getMetadata().getName()).block();
        assertThat(detail.targetReplyMarkdown()).isEqualTo("visitor follow-up");
        assertThat(detail.replyMarkdown()).isEqualTo("website reply");
    }
}
