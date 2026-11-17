package com.xhhao.clawbotnotify.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.channel.ChannelException;
import com.xhhao.clawbotnotify.channel.WechatProtocolClient;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;

class BindingRuntimeTest {
    private final ExtensionFixture fixture = new ExtensionFixture();
    private final ObjectMapper json = new ObjectMapper();
    private final WechatProtocolClient channel = mock(WechatProtocolClient.class);
    private final SecretCredentialStore secrets = new SecretCredentialStore(
            fixture.client, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
    private final com.xhhao.clawbotnotify.command.InboundCommandService commands =
            mock(com.xhhao.clawbotnotify.command.InboundCommandService.class);
    private final com.xhhao.clawbotnotify.comment.CommentDiscovery discovery =
            mock(com.xhhao.clawbotnotify.comment.CommentDiscovery.class);
    private final com.xhhao.clawbotnotify.delivery.MessageComposer composer =
            mock(com.xhhao.clawbotnotify.delivery.MessageComposer.class);
    private final com.xhhao.clawbotnotify.command.CommentReplyExecutor replies =
            mock(com.xhhao.clawbotnotify.command.CommentReplyExecutor.class);
    private final com.xhhao.clawbotnotify.comment.ReplyProcessingSynchronizer processing =
            mock(com.xhhao.clawbotnotify.comment.ReplyProcessingSynchronizer.class);
    private final com.xhhao.clawbotnotify.comment.ReplyDiscovery replyNotifications =
            mock(com.xhhao.clawbotnotify.comment.ReplyDiscovery.class);
    private final BindingRuntime runtime = new BindingRuntime(
            new com.xhhao.clawbotnotify.channel.WechatReceiver(
                    new BindingRepository(fixture.client), secrets, channel, commands),
            new com.xhhao.clawbotnotify.delivery.OutboundDeliveryWorker(
                    new BindingRepository(fixture.client), fixture.client, secrets, channel, composer),
            discovery,
            replies,
            processing,
            replyNotifications);

    @BeforeEach
    void setup() {
        when(replyNotifications.tick(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(processing.tick(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(replies.recoverInterrupted()).thenReturn(Mono.empty());
        when(commands.capture(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.empty());
        when(discovery.tick(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(composer.compose(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just("test content"));
        var binding = new WechatBinding();
        var metadata = new Metadata();
        metadata.setName("primary");
        binding.setMetadata(metadata);
        var spec = new WechatBinding.Spec();
        spec.setGeneration(1);
        spec.setEnabled(true);
        spec.setBotId("bot");
        spec.setOwnerWechatUserId("owner");
        spec.setCredentialRef("clawbot-ref");
        spec.setApiBaseUrl("https://ilinkai.weixin.qq.com");
        binding.setSpec(spec);
        fixture.put(binding, true);
        secrets.create("clawbot-ref", Map.of("botToken", "token")).block();
        when(channel.poll(anyString(), anyString(), nullable(String.class))).thenReturn(Mono.never());
    }

    @AfterEach
    void stop() {
        runtime.stop().block();
    }

    @Test
    void transientInboundQueryFailureRecoversWithoutRestartAndKeepsTheBatchUntilCaptured() throws Exception {
        String batch = """
                {"msgs":[{"message_id":"retry-inbound","from_user_id":"owner","message_type":1,
                "message_state":2,"create_time_ms":1794556000000,"context_token":"context",
                "item_list":[{"type":1,"text_item":{"text":"帮助"}}]}],"get_updates_buf":"saved-cursor"}
                """;
        secrets.update("clawbot-ref", data -> data.put("pendingBatch", batch)).block();
        when(commands.capture(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        Mono.error(new IllegalArgumentException("No index found with name: spec.kind")), Mono.empty());
        when(channel.poll(anyString(), anyString(), nullable(String.class)))
                .thenReturn(Mono.just(json.readTree("{\"msgs\":[],\"get_updates_buf\":\"saved-cursor\"}")));
        runtime.start();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(fixture.get(WechatBinding.class, "primary").getStatus().getLastErrorCode())
                    .isEqualTo("STORAGE_OR_CONNECTION_FAILED");
            assertThat(secrets.read("clawbot-ref").block()).containsKey("pendingBatch");
        });
        await().atMost(Duration.ofSeconds(9)).untilAsserted(() -> {
            assertThat(fixture.get(WechatBinding.class, "primary").getStatus().getConnectionState())
                    .isEqualTo(WechatBinding.ConnectionState.ACTIVE);
            assertThat(fixture.get(WechatBinding.class, "primary").getStatus().getLastErrorCode())
                    .isNull();
            assertThat(secrets.read("clawbot-ref").block()).doesNotContainKey("pendingBatch");
        });
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.times(2))
                .capture(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void context() {
        secrets.update("clawbot-ref", values -> values.put("context", "context"))
                .block();
    }

    private OutboundTask task(String name, long generation, OutboundTask.State state) {
        var task = new OutboundTask();
        var metadata = new Metadata();
        metadata.setName(name);
        task.setMetadata(metadata);
        var spec = new OutboundTask.Spec();
        spec.setState(state);
        spec.setGeneration(generation);
        spec.setTargetUserId("owner");
        spec.setClientId("stable-id");
        task.setSpec(spec);
        return fixture.put(task, true);
    }

    private OutboundTask.State state(String name) {
        return fixture.get(OutboundTask.class, name).getSpec().getState();
    }

    private void waitFor(String name, OutboundTask.State expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(state(name)).isEqualTo(expected));
    }

    @Test
    void missingContextWaitsAndOnlySendsAfterValidContextArrives() {
        var sends = new AtomicInteger();
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    sends.incrementAndGet();
                    return Mono.just("message-id");
                });
        task("test", 1, OutboundTask.State.PENDING);
        runtime.start();
        waitFor("test", OutboundTask.State.WAITING_CONTEXT);
        assertThat(sends).hasValue(0);
        context();
        waitFor("test", OutboundTask.State.ACCEPTED);
        assertThat(sends).hasValue(1);
        assertThat(fixture.get(OutboundTask.class, "test").getSpec().getClientId())
                .isEqualTo("stable-id");
    }

    @Test
    void ambiguousSendIsRecordedAndNeverAutomaticallyReplayedOnRestart() {
        var sends = new AtomicInteger();
        context();
        task("test", 1, OutboundTask.State.PENDING);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    sends.incrementAndGet();
                    return Mono.error(new ChannelException(ChannelException.Code.TIMEOUT));
                });
        runtime.start();
        waitFor("test", OutboundTask.State.UNKNOWN);
        runtime.stop().block();
        runtime.start();
        await().during(Duration.ofMillis(1200))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sends).hasValue(1));
        assertThat(state("test")).isEqualTo(OutboundTask.State.UNKNOWN);
    }

    @Test
    void cancellationStopsTheHttpRequestAndConvertsSendingToUnknown() {
        var cancelled = new AtomicBoolean();
        context();
        task("test", 1, OutboundTask.State.PENDING);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.<String>never().doOnCancel(() -> cancelled.set(true)));
        runtime.start();
        waitFor("test", OutboundTask.State.SENDING);
        runtime.stop().block();
        assertThat(cancelled).isTrue();
        assertThat(state("test")).isEqualTo(OutboundTask.State.UNKNOWN);
    }

    @Test
    void oldGenerationCannotSendToTheCurrentAccount() {
        var sends = new AtomicInteger();
        context();
        task("old", 0, OutboundTask.State.PENDING);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    sends.incrementAndGet();
                    return Mono.just("id");
                });
        runtime.start();
        waitFor("old", OutboundTask.State.CANCELLED);
        assertThat(sends).hasValue(0);
    }

    @Test
    void failureToPersistTheSendingClaimPreventsHttpDispatch() {
        var sends = new AtomicInteger();
        context();
        task("test", 1, OutboundTask.State.PENDING);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    sends.incrementAndGet();
                    return Mono.just("id");
                });
        fixture.failUpdates = true;
        runtime.start();
        await().during(Duration.ofMillis(1200))
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(sends).hasValue(0));
        assertThat(state("test")).isEqualTo(OutboundTask.State.PENDING);
    }

    @Test
    void laterBindingStatusFailureCannotReclassifyAnAcceptedDelivery() {
        context();
        task("test", 1, OutboundTask.State.PENDING);
        when(channel.send(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    doAnswer(update -> Mono.error(new IllegalStateException("SIMULATED_BINDING_STATUS_FAILURE")))
                            .when(fixture.client)
                            .update(org.mockito.ArgumentMatchers.any(WechatBinding.class));
                    return Mono.just("id");
                });
        runtime.start();
        waitFor("test", OutboundTask.State.ACCEPTED);
        assertThat(fixture.get(OutboundTask.class, "test").getSpec().getUpstreamMessageId())
                .isEqualTo("id");
    }

    @Test
    void encryptedPendingBatchIsDrainedAfterRestartBeforePollingAnotherBatch() throws Exception {
        String batch =
                "{\"msgs\":[{\"message_id\":18446744073709551615,\"from_user_id\":\"owner\",\"to_user_id\":\"bot\","
                        + "\"message_type\":1,\"message_state\":2,\"create_time_ms\":1000,\"context_token\":\"restored-context\","
                        + "\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"开始\"}}]}]}";
        secrets.update("clawbot-ref", values -> {
                    values.put("pendingBatch", batch);
                    values.put("cursor", "saved-cursor");
                })
                .block();
        var polled = new AtomicBoolean();
        when(channel.poll(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var saved = secrets.read("clawbot-ref").block();
            assertThat(saved).doesNotContainKey("pendingBatch").containsEntry("context", "restored-context");
            assertThat(call.<String>getArgument(2)).isEqualTo("saved-cursor");
            polled.set(true);
            return Mono.never();
        });
        runtime.start();
        await().atMost(Duration.ofSeconds(3)).untilTrue(polled);
        assertThat(fixture.get(WechatBinding.class, "primary").getStatus().getConnectionState())
                .isEqualTo(WechatBinding.ConnectionState.ACTIVE);
    }

    @Test
    void unauthorizedOrIncompleteMessagesCannotSupplySessionContext() throws Exception {
        var binding = fixture.get(WechatBinding.class, "primary");
        String template =
                "{\"message_id\":\"1\",\"from_user_id\":\"%s\",\"to_user_id\":\"bot\",\"message_type\":1,\"message_state\":%d,"
                        + "\"create_time_ms\":1000,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"开始\"}}]}";
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(
                        binding, json.readTree(template.formatted("stranger", 2))))
                .isFalse();
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(
                        binding, json.readTree(template.formatted("owner", 1))))
                .isFalse();
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(
                        binding, json.readTree(template.formatted("owner", 2))))
                .isTrue();
    }

    @Test
    void authenticatedOwnerTextAllowsOmittedRecipientButRejectsOtherTargetsAndGroups() throws Exception {
        var binding = fixture.get(WechatBinding.class, "primary");
        var message = (com.fasterxml.jackson.databind.node.ObjectNode)
                json.readTree("{\"message_id\":1,\"from_user_id\":\"owner\",\"message_type\":1,\"message_state\":2,"
                        + "\"create_time_ms\":1000,\"item_list\":[{\"type\":1,\"text_item\":{\"text\":\"开始\"}}]}");
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(binding, message))
                .isTrue();
        message.put("to_user_id", "");
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(binding, message))
                .isTrue();
        message.put("to_user_id", "another-bot");
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(binding, message))
                .isFalse();
        message.remove("to_user_id");
        message.put("group_id", "group");
        assertThat(com.xhhao.clawbotnotify.channel.WechatReceiver.isOwnerText(binding, message))
                .isFalse();
    }
}
