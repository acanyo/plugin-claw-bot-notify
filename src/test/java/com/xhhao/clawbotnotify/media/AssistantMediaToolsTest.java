package com.xhhao.clawbotnotify.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.assistant.draft.AssistantConfirmationExecutor;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.infra.ExternalUrlSupplier;

class AssistantMediaToolsTest {
    final ObjectMapper json = new ObjectMapper();
    final MediaFileStore.Image image = new MediaFileStore.Image(
            "image-" + "a".repeat(64),
            "photo.png",
            "image/png",
            70,
            "hash",
            Instant.now().plusSeconds(3000));
    final HaloMediaClient.Attachment attachment =
            new HaloMediaClient.Attachment("attachment-one", "https://site.test/upload/photo.png", "image/png");

    class Setup {
        final AssistantFixture f = new AssistantFixture();
        final WechatMediaInbox inbox = mock(WechatMediaInbox.class);
        final MediaFileStore files = mock(MediaFileStore.class);
        final HaloMediaClient halo = mock(HaloMediaClient.class);
        final AssistantMediaTools media;
        final AssistantConfirmationExecutor confirm;

        Setup() throws Exception {
            f.binding.getSpec().setCredentialRef("credentials");
            f.secrets.create("credentials", Map.of("haloPat", "private-pat")).block();
            when(inbox.list(any(), any())).thenReturn(Mono.just(List.of(image)));
            when(inbox.encode(any())).thenAnswer(call -> json.writeValueAsString(call.getArgument(0)));
            when(files.read(image)).thenReturn(Mono.just(new byte[] {1, 2, 3}));
            when(halo.upload(anyString(), eq(image), any())).thenReturn(Mono.just(attachment));
            var moments = new com.xhhao.clawbotnotify.halo.BotMomentPublisher(
                    new com.xhhao.clawbotnotify.halo.MomentAuthors(f.resources.client), halo);
            media = new AssistantMediaTools(inbox, files, halo, f.secrets, f.drafts, moments);
            var url = mock(ExternalUrlSupplier.class);
            when(url.getRaw()).thenReturn(URI.create("http://localhost:18091").toURL());
            var tools = new AssistantTools(f.cli, url, f.authority, f.settings, f.secrets, f.drafts, media, moments);
            confirm = new AssistantConfirmationExecutor(
                    f.resources.client, f.secrets, f.drafts, f.authority, f.settings, tools, f.replies);
        }

        InboundCommand preview(String tool) {
            var source = f.command("preview", "图片操作");
            media.execute(
                            f.binding,
                            source,
                            tool,
                            Map.of("mediaIds", List.of(image.id()), "content", "准确正文", "visible", "PRIVATE"),
                            true)
                    .block();
            return source;
        }

        InboundCommand confirmation(String id, InboundCommand source) {
            var draft = f.drafts.load(f.binding, source.getMetadata().getName()).block();
            var command = f.command(id, AssistantDrafts.confirmation(draft));
            command.getSpec().setType(InboundCommand.Type.AI_CONFIRM);
            command.getSpec().setPreviewOf(source.getMetadata().getName());
            return f.resources.put(command, false);
        }
    }

    @Test
    void stageHasNoSideEffectsAndConcurrentConfirmationUploadsAndPublishesOnce() throws Exception {
        var s = new Setup();
        when(s.halo.moment(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(call -> {
                    var result = json.createObjectNode();
                    result.putObject("metadata").put("name", (String) call.getArgument(1));
                    result.putObject("spec").put("owner", "clawbot");
                    return Mono.just(result);
                });
        var source = s.preview("moment_create_images");
        verifyNoInteractions(s.halo, s.files);
        var commands = List.of(s.confirmation("one", source), s.confirmation("two", source));
        var results = Flux.fromIterable(commands)
                .flatMap(c -> s.confirm.process(s.f.binding, c).subscribeOn(Schedulers.parallel()))
                .collectList()
                .block();
        assertThat(results)
                .filteredOn(c -> c.getSpec().getWriteState() == InboundCommand.WriteState.SUCCEEDED)
                .hasSize(1);
        verify(s.halo, times(1)).upload(eq("private-pat"), eq(image), any());
        verify(s.halo, times(1))
                .moment(
                        eq("private-pat"),
                        anyString(),
                        eq("clawbot"),
                        eq("准确正文"),
                        eq("PRIVATE"),
                        eq(List.of(attachment)));
        verifyNoInteractions(s.f.model, s.f.cli);
    }

    @Test
    void uploadOnlyCachesAttachmentForLaterProposals() throws Exception {
        var s = new Setup();
        var source = s.preview("attachment_upload");
        var command = s.confirmation("upload", source);
        var done = s.confirm.process(s.f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.SUCCEEDED);
        s.confirm.process(s.f.binding, done).block();
        assertThat(s.f.secrets.read("credentials").block()).containsKey("uploaded:" + image.id());
        verify(s.halo, times(1)).upload(anyString(), any(), any());
        verify(s.halo, never()).moment(anyString(), anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void failedFinalWriteReportsCompletedAttachmentAndDoesNotRetry() throws Exception {
        var s = new Setup();
        when(s.halo.moment(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(Mono.error(new IllegalStateException("MEDIA_PERMISSION_DENIED")));
        var source = s.preview("moment_create_images");
        var command = s.confirmation("failed", source);
        var done = s.confirm.process(s.f.binding, command).block();
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.UNKNOWN);
        assertThat(s.f.secrets.read(command.getSpec().getBodyRef()).block().get("aiResponse"))
                .contains("attachment-one", "请先到 Halo 核查");
        s.confirm.process(s.f.binding, done).block();
        verify(s.halo, times(1)).upload(anyString(), any(), any());
        verify(s.halo, times(1)).moment(anyString(), anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void changedCoverTargetIsRejectedBeforeAnyUpload() throws Exception {
        var s = new Setup();
        var old = json.readTree(
                "{\"metadata\":{\"name\":\"post\",\"version\":1},\"spec\":{\"title\":\"文章\",\"cover\":\"old\"}}");
        var changed = json.readTree(
                "{\"metadata\":{\"name\":\"post\",\"version\":2},\"spec\":{\"title\":\"更新\",\"cover\":\"old\"}}");
        when(s.halo.post("private-pat", "post")).thenReturn(Mono.just(old), Mono.just(changed));
        var source = s.f.command("cover-preview", "设封面");
        s.media
                .execute(
                        s.f.binding,
                        source,
                        "post_set_cover",
                        Map.of("name", "post", "mediaIds", List.of(image.id())),
                        true)
                .block();
        var done = s.confirm
                .process(s.f.binding, s.confirmation("cover-confirm", source))
                .block();
        assertThat(done.getSpec().getErrorCode()).isEqualTo("MEDIA_TARGET_CHANGED");
        assertThat(done.getSpec().getWriteState()).isEqualTo(InboundCommand.WriteState.REJECTED);
        verify(s.halo, never()).upload(anyString(), any(), any());
        verify(s.halo, never()).setCover(anyString(), any(), anyString());
    }

    @Test
    void expiredImagesForeignIdsAndWriteSwitchAreRejected() throws Exception {
        var s = new Setup();
        var source = s.f.command("preview", "图片");
        assertThatThrownBy(() -> s.media
                        .execute(s.f.binding, source, "attachment_upload", Map.of("mediaIds", List.of("foreign")), true)
                        .block())
                .hasMessage("MEDIA_EXPIRED");
        assertThatThrownBy(() -> s.media
                        .execute(
                                s.f.binding,
                                source,
                                "attachment_upload",
                                Map.of("mediaIds", List.of(image.id())),
                                false)
                        .block())
                .hasMessage("AI_WRITES_DISABLED");
        var expired = new MediaFileStore.Image(
                image.id(),
                image.filename(),
                image.mediaType(),
                image.size(),
                image.sha256(),
                Instant.now().minusSeconds(1));
        var draft = new AssistantDrafts.Draft("attachment_upload", Map.of("images", List.of(expired)), null, false);
        assertThatThrownBy(() -> s.media.confirmed(s.f.binding, source, draft).block())
                .hasMessage("MEDIA_EXPIRED");
        verifyNoInteractions(s.halo, s.files);
    }
}
