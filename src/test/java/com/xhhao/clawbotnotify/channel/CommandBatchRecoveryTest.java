package com.xhhao.clawbotnotify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;

class CommandBatchRecoveryTest {
    @Test
    void queueStorageFailureKeepsEncryptedBatchAndRestartCapturesItBeforePolling() {
        var fixture = new ExtensionFixture();
        var binding = new WechatBinding();
        var meta = new Metadata();
        meta.setName("primary");
        binding.setMetadata(meta);
        var spec = new WechatBinding.Spec();
        spec.setEnabled(true);
        spec.setGeneration(1);
        spec.setBotId("bot");
        spec.setOwnerWechatUserId("owner");
        spec.setCredentialRef("credentials");
        spec.setApiBaseUrl("https://ilinkai.weixin.qq.com");
        binding.setSpec(spec);
        fixture.put(binding, true);
        var store = new SecretCredentialStore(
                fixture.client, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        String batch = """
            {"msgs":[{"message_id":18446744073709551615,"from_user_id":"owner","message_type":1,"message_state":2,"create_time_ms":1794556000000,"context_token":"context","item_list":[{"type":1,"text_item":{"text":"帮助"}}]}],"get_updates_buf":"saved-cursor"}
            """;
        store.create("credentials", Map.of("botToken", "token", "pendingBatch", batch, "cursor", "saved-cursor"))
                .block();
        var channel = mock(WechatProtocolClient.class);
        when(channel.poll(anyString(), anyString(), nullable(String.class))).thenAnswer(call -> {
            assertThat(store.read("credentials").block()).doesNotContainKey("pendingBatch");
            assertThat(call.getArgument(2, String.class)).isEqualTo("saved-cursor");
            return Mono.never();
        });
        var repository = new BindingRepository(fixture.client);
        var commands = com.xhhao.clawbotnotify.support.CommandServices.reads(fixture, store);
        var receiver = new WechatReceiver(repository, store, channel, commands);
        fixture.failCreatesFor = OutboundTask.class;
        var first = receiver.tick(() -> true).subscribe();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(
                                repository.current().block().getStatus().getLastErrorCode())
                        .isEqualTo("STORAGE_OR_CONNECTION_FAILED"));
        first.dispose();
        assertThat(store.read("credentials").block()).containsKey("pendingBatch");
        fixture.failCreatesFor = null;
        var recovered = new WechatReceiver(
                repository, store, channel, com.xhhao.clawbotnotify.support.CommandServices.reads(fixture, store));
        var second = recovered.tick(() -> true).subscribe();
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(
                        () -> assertThat(store.read("credentials").block()).doesNotContainKey("pendingBatch"));
        second.dispose();
        assertThat(fixture.client
                        .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(10))
                        .block()
                        .getTotal())
                .isEqualTo(1);
        assertThat(fixture.client
                        .listBy(InboundCommand.class, new ListOptions(), PageRequestImpl.ofSize(10))
                        .block()
                        .getItems()
                        .getFirst()
                        .getSpec()
                        .getState())
                .isEqualTo(InboundCommand.State.QUEUED);
    }
}
