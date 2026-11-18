package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.binding.BindingRepository;
import java.net.URI;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;

class ManagedCliCredentialsTest {
    @Test
    void readsCurrentEncryptedTokenAndRejectsChangedBinding() throws Exception {
        var f = new AssistantFixture();
        f.binding.getSpec().setEnabled(true);
        f.secrets
                .update("fixture-credentials", values -> values.put("haloPat", "first-token"))
                .block();
        var bindings = mock(BindingRepository.class);
        when(bindings.current()).thenReturn(Mono.just(f.binding));
        var url = mock(ExternalUrlSupplier.class);
        when(url.getRaw()).thenReturn(URI.create("http://localhost:18091").toURL());
        var credentials = new ManagedCliCredentials(bindings, f.secrets, url);
        assertThat(new ObjectMapper()
                        .readTree(credentials.header("clawbot").block())
                        .path("token")
                        .asText())
                .isEqualTo("first-token");
        f.secrets
                .update("fixture-credentials", values -> values.put("haloPat", "rotated-token"))
                .block();
        assertThat(credentials.header("clawbot").block()).contains("rotated-token");
        var old = new com.xhhao.clawbotnotify.model.WechatBinding();
        old.setSpec(new com.xhhao.clawbotnotify.model.WechatBinding.Spec());
        old.getSpec().setGeneration(2);
        assertThatThrownBy(() -> credentials
                        .header("clawbot")
                        .contextWrite(context -> context.put(ManagedCliCredentials.BINDING_CONTEXT, old))
                        .block())
                .hasMessage("BINDING_CHANGED");
        f.binding.getSpec().setEnabled(false);
        assertThatThrownBy(() -> credentials.header("clawbot").block()).hasMessage("CLI_BINDING_REQUIRED");
    }
}
