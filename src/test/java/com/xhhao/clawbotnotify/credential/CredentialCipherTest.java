package com.xhhao.clawbotnotify.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {
    private final CredentialCipher cipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptedPayloadRoundTripsWithIndependentNonces() {
        byte[] raw = "bot-token-and-context".getBytes(StandardCharsets.UTF_8);
        byte[] first = cipher.encrypt("binding-a", raw);
        byte[] second = cipher.encrypt("binding-a", raw);
        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("bot-token-and-context");
        assertThat(cipher.decrypt("binding-a", first)).isEqualTo(raw);
    }

    @Test
    void ciphertextCannotBeMovedToAnotherSecretOrTamperedWith() {
        byte[] data = cipher.encrypt("binding-a", new byte[] {1, 2, 3});
        assertThatThrownBy(() -> cipher.decrypt("binding-b", data)).hasMessage("CREDENTIAL_DECRYPTION_FAILED");
        data[data.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("binding-a", data)).hasMessage("CREDENTIAL_DECRYPTION_FAILED");
    }

    @Test
    void missingOrInvalidKeyKeepsStorageClosed() {
        var unavailable = new CredentialCipher("invalid");
        assertThat(unavailable.available()).isFalse();
        assertThatThrownBy(() -> unavailable.encrypt("ref", new byte[] {1})).hasMessage("CREDENTIAL_KEY_REQUIRED");
    }

    @Test
    void anotherMasterKeyCannotDecryptExistingCredentials() {
        var other = new CredentialCipher(Base64.getEncoder()
                .encodeToString("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)));
        var payload = cipher.encrypt("ref", new byte[] {1});
        assertThatThrownBy(() -> other.decrypt("ref", payload)).hasMessage("CREDENTIAL_DECRYPTION_FAILED");
    }
}
