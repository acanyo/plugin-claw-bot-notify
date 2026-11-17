package com.xhhao.clawbotnotify.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Secret;

class AssistantMaintenanceTest {
    InboundCommand old(AssistantFixture f, String name, InboundCommand.WriteState state) {
        var command = f.command(name, "应当清理的私密正文");
        command.getSpec().setState(InboundCommand.State.QUEUED);
        command.getSpec().setWriteState(state);
        command.getSpec().setReceivedAt(Instant.now().minusSeconds(90L * 86400));
        return f.resources.put(command, false);
    }

    AssistantMaintenance cleaner(AssistantFixture f) {
        return new AssistantMaintenance(f.resources.client, f.secrets, f.settings, f.history, media());
    }

    private static com.xhhao.clawbotnotify.media.WechatMediaInbox media() {
        var media = org.mockito.Mockito.mock(com.xhhao.clawbotnotify.media.WechatMediaInbox.class);
        org.mockito.Mockito.when(media.cleanup()).thenReturn(reactor.core.publisher.Mono.empty());
        return media;
    }

    @Test
    void oldTerminalBodiesAreErasedButTombstonesCredentialsAndUncertainResultsSurvive() throws Exception {
        var f = new AssistantFixture();
        var success = old(f, "success", InboundCommand.WriteState.SUCCEEDED);
        var unknown = old(f, "unknown", InboundCommand.WriteState.UNKNOWN);
        var partial = old(f, "partial", InboundCommand.WriteState.PARTIAL);
        var pending = old(f, "pending", InboundCommand.WriteState.READY);
        var preview = old(f, "preview", InboundCommand.WriteState.AWAITING_APPROVAL);
        f.secrets
                .update(
                        preview.getSpec().getBodyRef(),
                        values -> values.put(
                                "proposalExpiresAt",
                                Instant.now().minusSeconds(1).toString()))
                .block();
        f.secrets.create("credentials", Map.of("haloPat", "never-delete-this")).block();
        var cleaner = cleaner(f);
        assertThat(cleaner.tick().block()).isEqualTo(2);
        assertThat(f.resources
                        .client
                        .fetch(Secret.class, success.getSpec().getBodyRef())
                        .block())
                .isNull();
        assertThat(f.resources
                        .client
                        .fetch(Secret.class, preview.getSpec().getBodyRef())
                        .block())
                .isNull();
        assertThat(f.resources
                        .client
                        .get(InboundCommand.class, "success")
                        .block()
                        .getSpec()
                        .getCleanedAt())
                .isNotNull();
        for (var protectedCommand : List.of(unknown, partial, pending)) {
            assertThat(f.secrets.read(protectedCommand.getSpec().getBodyRef()).block())
                    .containsKey("body");
            assertThat(f.resources
                            .client
                            .get(
                                    InboundCommand.class,
                                    protectedCommand.getMetadata().getName())
                            .block()
                            .getSpec()
                            .getCleanedAt())
                    .isNull();
        }
        assertThat(f.secrets.read("credentials").block()).containsEntry("haloPat", "never-delete-this");
        assertThat(cleaner.tick().block()).isZero();
        assertThat(cleaner.lastRunAt()).isNotNull();
        assertThat(cleaner.errorCode()).isNull();
    }

    @Test
    void pendingOrUnknownReceiptAndSharedModerationBodyPreventErasure() throws Exception {
        var f = new AssistantFixture();
        var waiting = old(f, "waiting", InboundCommand.WriteState.SUCCEEDED);
        var outbound = new OutboundTask();
        var metadata = new Metadata();
        metadata.setName("out-" + StableIds.hash("primary", "3", "COMMAND", "waiting"));
        outbound.setMetadata(metadata);
        var spec = new OutboundTask.Spec();
        spec.setState(OutboundTask.State.WAITING_CONTEXT);
        outbound.setSpec(spec);
        f.resources.put(outbound, true);
        var shared = old(f, "shared", InboundCommand.WriteState.SUCCEEDED);
        var approval = old(f, "approval", InboundCommand.WriteState.AWAITING_APPROVAL);
        approval.getSpec().setType(InboundCommand.Type.REPLY);
        approval.getSpec().setBodyRef(shared.getSpec().getBodyRef());
        f.resources.put(approval, false);
        assertThat(cleaner(f).tick().block()).isZero();
        assertThat(f.secrets.read(waiting.getSpec().getBodyRef()).block()).containsKey("body");
        assertThat(f.secrets.read(shared.getSpec().getBodyRef()).block()).containsKey("body");
    }

    @Test
    void aStillValidPreviewSurvivesEvenIfTheMessagePredatesTheRetentionCutoff() throws Exception {
        var f = new AssistantFixture();
        var source = old(f, "active-preview", InboundCommand.WriteState.AWAITING_APPROVAL);
        f.secrets
                .update(
                        source.getSpec().getBodyRef(),
                        values -> values.put(
                                "proposalExpiresAt",
                                Instant.now().plusSeconds(3600).toString()))
                .block();
        assertThat(cleaner(f).tick().block()).isZero();
        assertThat(f.secrets.read(source.getSpec().getBodyRef()).block()).containsKey("body");
    }

    @Test
    void settingsFailureNeverDeletesAndIsVisibleInRuntimeStatus() throws Exception {
        var f = new AssistantFixture();
        var command = old(f, "old", InboundCommand.WriteState.SUCCEEDED);
        when(f.settings.get()).thenReturn(Mono.error(new IllegalStateException("AI_SETTINGS_INVALID")));
        var cleaner = cleaner(f);
        assertThatThrownBy(() -> cleaner.tick().block()).hasMessage("AI_SETTINGS_INVALID");
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).containsKey("body");
        assertThat(cleaner.errorCode()).isEqualTo("AI_CLEANUP_FAILED");
    }

    @Test
    void maintenancePreservesIdleHistoryAndTrimsOnlyWhenCapacityIsReduced() throws Exception {
        var f = new AssistantFixture();
        var metadata = new Metadata();
        metadata.setName("primary");
        f.binding.setMetadata(metadata);
        f.resources.put(f.binding, true);
        f.history.messages(f.binding, f.config).block();
        f.history
                .append(f.binding, f.config, "old", "历史私密正文", List.of(ModelMessage.assistant("历史私密答复")))
                .block();
        String ref = "clawbot-chat-" + StableIds.hash("3", "bot", "owner", "admin");
        f.secrets
                .update(
                        ref,
                        data -> data.put(
                                "updatedAt", Instant.now().minusSeconds(7200).toString()))
                .block();
        // Previous generations are also found by their plugin-owned history prefix.
        f.binding.getSpec().setGeneration(4);
        var cleaner = cleaner(f);
        cleaner.tick().block();
        assertThat(f.secrets.read(ref).block()).containsKey("turns");
        f.config.setMaxContextChars(1);
        cleaner.tick().block();
        assertThat(f.secrets.read(ref).block()).containsEntry("turns", "[]");
    }

    @Test
    void clearedRecordsDisappearFromBothListsWhileDeduplicationTombstonesSurvive() throws Exception {
        var f = new AssistantFixture();
        var command = old(f, "history", InboundCommand.WriteState.SUCCEEDED);
        var queue = new com.xhhao.clawbotnotify.delivery.OutboundQueue(f.resources.client);
        var receipt = queue.enqueue(f.binding, "COMMAND", "history", spec -> spec.setCommandName("history"))
                .block();
        receipt.getSpec().setState(OutboundTask.State.ACCEPTED);
        receipt.getSpec().setFinishedAt(Instant.now().minusSeconds(90L * 86400));
        f.resources.put(receipt, false);
        assertThat(cleaner(f).tick().block()).isEqualTo(1);
        var bindingService = mock(com.xhhao.clawbotnotify.binding.BindingService.class);
        var records = new com.xhhao.clawbotnotify.command.CommandRecordService(
                f.resources.client,
                bindingService,
                new com.xhhao.clawbotnotify.comment.CommentLinkRepository(f.resources.client),
                f.secrets,
                f.comments);
        var deliveries = new com.xhhao.clawbotnotify.delivery.TestDeliveryService(f.resources.client, bindingService);
        assertThat(records.list(1, 20, null, null).block().total()).isZero();
        assertThat(deliveries.list(1, 20).block().total()).isZero();
        assertThat(f.resources
                        .client
                        .get(InboundCommand.class, command.getMetadata().getName())
                        .block())
                .isNotNull();
        var replay = queue.enqueue(f.binding, "COMMAND", "history", spec -> spec.setCommandName("history"))
                .block();
        assertThat(replay.getSpec().getState()).isEqualTo(OutboundTask.State.ACCEPTED);
        assertThat(replay.getSpec().getCleanedAt()).isNotNull();
        verifyNoInteractions(f.cli, f.model, f.halo);
    }
}
