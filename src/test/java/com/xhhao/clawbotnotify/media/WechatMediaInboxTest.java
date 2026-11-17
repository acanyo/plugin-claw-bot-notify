package com.xhhao.clawbotnotify.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.channel.WechatReceiver;
import com.xhhao.clawbotnotify.model.InboundCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Secret;

class WechatMediaInboxTest {
    @Test
    void receiptDownloadsOnlyOnceKeepsKeysOutOfStorageAndDoesNotUpload() throws Exception {
        var f = new AssistantFixture();
        f.binding.getSpec().setCredentialRef("credentials");
        f.secrets.create("credentials", Map.of("haloPat", "private-pat")).block();
        var files = mock(MediaFileStore.class);
        var downloader = mock(WechatMediaDownloader.class);
        when(downloader.download(any())).thenReturn(Mono.just(new byte[] {1, 2, 3}));
        when(files.save(anyString(), any()))
                .thenAnswer(call -> Mono.just(new MediaFileStore.Image(
                        call.getArgument(0),
                        "photo.png",
                        "image/png",
                        3,
                        "hash",
                        Instant.now().plusSeconds(100))));
        var inbox = new WechatMediaInbox(f.secrets, files, downloader, f.authority, f.resources.client);
        var command = f.command("image-message", "收到图片");
        command.getSpec().setType(InboundCommand.Type.AI_MEDIA);
        var message = new ObjectMapper()
                .readTree(
                        "{\"item_list\":[{\"type\":2,\"image_item\":{\"aeskey\":\"private-aes-key\",\"media\":{\"encrypt_query_param\":\"private-cdn-query\"}}}]}");
        inbox.receive(f.binding, command, message).block();
        inbox.receive(f.binding, command, message).block();
        inbox.acknowledge(command).block();
        assertThat(inbox.list(f.binding, command).block()).hasSize(1);
        var data = f.secrets.read(command.getSpec().getBodyRef()).block();
        assertThat(data.get("aiResponse")).contains("尚未上传", "24 小时");
        assertThat(data.toString()).doesNotContain("private-aes-key", "private-cdn-query");
        assertThat(f.resources
                        .client
                        .get(Secret.class, command.getSpec().getBodyRef())
                        .block()
                        .toString())
                .doesNotContain("photo.png", "hash");
        verify(downloader, times(1)).download(any());
        verifyNoInteractions(f.model, f.cli, f.halo);
        var later = f.command("later", "使用最近图片");
        assertThat(inbox.list(f.binding, later).block()).hasSize(1);
        f.binding.getSpec().setCredentialRef("new-generation");
        f.secrets.create("new-generation", Map.of()).block();
        assertThat(inbox.list(f.binding, later).block()).isEmpty();
    }

    @Test
    void receiverAcceptsOnlyOwnerImageMessagesAndRejectsOtherKinds() throws Exception {
        var f = new AssistantFixture();
        var message = new ObjectMapper().createObjectNode();
        message.put("message_id", "photo-message")
                .put("from_user_id", "owner")
                .put("message_type", 1)
                .put("message_state", 2)
                .put("create_time_ms", Instant.now().toEpochMilli());
        var item = message.putArray("item_list").addObject().put("type", 2);
        item.putObject("image_item").putObject("media").put("encrypt_query_param", "query");
        assertThat(WechatReceiver.isOwnerMessage(f.binding, message)).isTrue();
        assertThat(WechatReceiver.isOwnerText(f.binding, message)).isFalse();
        message.put("from_user_id", "stranger");
        assertThat(WechatReceiver.isOwnerMessage(f.binding, message)).isFalse();
        message.put("from_user_id", "owner");
        item.put("type", 3);
        assertThat(WechatReceiver.isOwnerMessage(f.binding, message)).isFalse();
    }

    @Test
    void expiredMetadataAndUploadCacheArePruned() throws Exception {
        var f = new AssistantFixture();
        f.binding.getSpec().setCredentialRef("credentials");
        f.binding.setMetadata(new run.halo.app.extension.Metadata());
        f.binding.getMetadata().setName("primary");
        f.resources.put(f.binding, true);
        f.secrets.create("credentials", Map.of()).block();
        var files = mock(MediaFileStore.class);
        when(files.cleanup()).thenReturn(Mono.empty());
        var inbox = new WechatMediaInbox(
                f.secrets, files, mock(WechatMediaDownloader.class), f.authority, f.resources.client);
        var image = new MediaFileStore.Image(
                "old", "old.png", "image/png", 10, "hash", Instant.now().minusSeconds(1));
        f.secrets
                .update("credentials", data -> {
                    data.put("recentMedia", inbox.encode(List.of(image)));
                    data.put("uploaded:old", "attachment");
                })
                .block();
        inbox.cleanup().block();
        assertThat(f.secrets.read("credentials").block())
                .containsEntry("recentMedia", "[]")
                .doesNotContainKey("uploaded:old");
        verify(files).cleanup();
    }
}
