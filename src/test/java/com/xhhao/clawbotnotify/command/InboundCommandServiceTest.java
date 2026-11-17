package com.xhhao.clawbotnotify.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;

class InboundCommandServiceTest {
    private final ExtensionFixture fixture = new ExtensionFixture();
    private final WechatBinding binding = new WechatBinding();
    private final ObjectMapper json =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private InboundCommandService service() {
        return com.xhhao.clawbotnotify.support.CommandServices.reads(
                fixture, org.mockito.Mockito.mock(com.xhhao.clawbotnotify.credential.CredentialStore.class));
    }

    InboundCommandServiceTest() {
        var spec = new WechatBinding.Spec();
        spec.setGeneration(1);
        spec.setBotId("bot");
        spec.setOwnerWechatUserId("owner");
        binding.setSpec(spec);
    }

    private com.fasterxml.jackson.databind.JsonNode message(String from, String text) throws Exception {
        var message = json.createObjectNode();
        message.put("message_id", new java.math.BigInteger("18446744073709551615"));
        message.put("from_user_id", from);
        message.put("message_type", 1);
        message.put("message_state", 2);
        message.put("create_time_ms", 1794556000000L);
        message.putArray("item_list")
                .addObject()
                .put("type", 1)
                .putObject("text_item")
                .put("text", text);
        return message;
    }

    private <T extends run.halo.app.extension.Extension> run.halo.app.extension.ListResult<T> rows(Class<T> type) {
        return fixture.client
                .listBy(type, new ListOptions(), PageRequestImpl.ofSize(100))
                .block();
    }

    @Test
    void replayAcrossServiceRestartAndUint64IdQueuesExactlyOneReceipt() throws Exception {
        var message = message("owner", "查看 c-7k2p");
        service().capture(binding, message).block();
        service().capture(binding, message).block();
        assertThat(rows(OutboundTask.class).getTotal()).isEqualTo(1);
        var record = rows(InboundCommand.class).getItems().getFirst();
        assertThat(record.getSpec().getShortCode()).isEqualTo("C-7K2P");
        assertThat(record.getSpec().getState()).isEqualTo(InboundCommand.State.QUEUED);
        String id = new InstanceIdentity(fixture.client).get().block();
        assertThat(new InstanceIdentity(fixture.client).get().block()).isEqualTo(id);
        binding.getSpec().setGeneration(2);
        service().capture(binding, message).block();
        assertThat(rows(OutboundTask.class).getTotal()).isEqualTo(2);
    }

    @Test
    void crashAfterCommandRecordBeforeQueueCreationIsRecoverable() throws Exception {
        var message = message("owner", "帮助");
        fixture.failCreatesFor = OutboundTask.class;
        assertThatThrownBy(() -> service().capture(binding, message).block()).hasMessage("SIMULATED_CREATE_FAILURE");
        assertThat(rows(InboundCommand.class).getItems().getFirst().getSpec().getState())
                .isEqualTo(InboundCommand.State.RECORDED);
        fixture.failCreatesFor = null;
        service().capture(binding, message).block();
        assertThat(rows(OutboundTask.class).getTotal()).isEqualTo(1);
        assertThat(rows(InboundCommand.class).getItems().getFirst().getSpec().getState())
                .isEqualTo(InboundCommand.State.QUEUED);
    }

    @Test
    void rejectsForeignIncompleteAndOrdinaryMessagesAndStoresNoWriteBody() throws Exception {
        service().capture(binding, message("foreign", "帮助")).block();
        var incomplete = (com.fasterxml.jackson.databind.node.ObjectNode) message("owner", "帮助");
        incomplete.put("message_state", 1);
        service().capture(binding, incomplete).block();
        service().capture(binding, message("owner", "普通聊天")).block();
        assertThat(rows(InboundCommand.class).getTotal()).isZero();
        service().capture(binding, message("owner", "回复 C-7K2P 私密回复正文")).block();
        assertThat(rows(InboundCommand.class).getItems().getFirst().getSpec().getType())
                .isEqualTo(InboundCommand.Type.UNSUPPORTED);
        assertThat(json.writeValueAsString(rows(InboundCommand.class)))
                .doesNotContain("私密回复正文", "18446744073709551615");
    }
}
