package com.xhhao.clawbotnotify.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Secret;

class SecretCredentialStoreTest {
    @Test
    void persistsEncryptedSecretsAcrossStoreInstancesWithoutReturningPlaintextInResources() {
        var fixture = new ExtensionFixture();
        var cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new SecretCredentialStore(fixture.client, cipher);
        store.create("clawbot-ref", Map.of("botToken", "sensitive-token", "cursor", "one"))
                .block();
        var secret = fixture.get(Secret.class, "clawbot-ref");
        assertThat(secret.getStringData()).isNull();
        assertThat(new String(secret.getData().get("encrypted-v1"), StandardCharsets.UTF_8))
                .doesNotContain("sensitive-token");
        store.update("clawbot-ref", values -> values.put("cursor", "two")).block();
        var restored = new SecretCredentialStore(fixture.client, cipher)
                .read("clawbot-ref")
                .block();
        assertThat(restored).containsEntry("botToken", "sensitive-token").containsEntry("cursor", "two");
        store.delete("clawbot-ref").block();
        assertThat(fixture.get(Secret.class, "clawbot-ref")).isNull();
    }
}
