package com.xhhao.clawbotnotify.credential;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${clawbot.master-key:${CLAWBOT_MASTER_KEY:}}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            decoded = new byte[0];
        }
        key = decoded.length == 32 ? decoded : null;
    }

    public boolean available() {
        return key != null;
    }

    public byte[] encrypt(String reference, byte[] value) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, reference, nonce);
            byte[] encrypted = cipher.doFinal(value);
            byte[] output = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return output;
        } catch (Exception e) {
            throw new IllegalStateException("CREDENTIAL_ENCRYPTION_FAILED");
        }
    }

    public byte[] decrypt(String reference, byte[] value) {
        requireKey();
        try {
            if (value.length < 28) throw new IllegalArgumentException();
            return cipher(Cipher.DECRYPT_MODE, reference, Arrays.copyOf(value, 12))
                    .doFinal(Arrays.copyOfRange(value, 12, value.length));
        } catch (Exception e) {
            throw new IllegalStateException("CREDENTIAL_DECRYPTION_FAILED");
        }
    }

    private Cipher cipher(int mode, String reference, byte[] nonce) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireKey() {
        if (!available()) throw new IllegalStateException("CREDENTIAL_KEY_REQUIRED");
    }
}
