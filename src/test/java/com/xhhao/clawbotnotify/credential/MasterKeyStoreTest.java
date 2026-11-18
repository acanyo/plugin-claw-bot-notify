package com.xhhao.clawbotnotify.credential;

import static org.assertj.core.api.Assertions.*;

import com.xhhao.clawbotnotify.storage.ClawBotDataDirectory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterKeyStoreTest {
    @TempDir
    Path temp;

    MasterKeyStore store() {
        return new MasterKeyStore(new ClawBotDataDirectory(temp.toString(), ""));
    }

    @Test
    void automaticKeySurvivesRestartAndDecryptsExistingSecrets() throws Exception {
        var first = new CredentialCipher("", store());
        assertThat(first.available()).isTrue();
        byte[] encrypted = first.encrypt("saved-secret", "original credential".getBytes());
        var restarted = new CredentialCipher("", store());
        assertThat(restarted.decrypt("saved-secret", encrypted)).isEqualTo("original credential".getBytes());
        Path key = temp.resolve("clawbot/master.key");
        assertThat(Base64.getDecoder().decode(Files.readString(key).strip())).hasSize(32);
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(key))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.getPosixFilePermissions(key.getParent()))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void concurrentInitializersUseTheSameKey() {
        var first = CompletableFuture.supplyAsync(() -> store().loadOrCreate());
        var second = CompletableFuture.supplyAsync(() -> store().loadOrCreate());
        assertThat(first.join()).isEqualTo(second.join());
    }

    @Test
    void corruptKeyIsNeverReplacedAndStorageStaysClosed() throws Exception {
        Path key = temp.resolve("clawbot/master.key");
        Files.createDirectories(key.getParent());
        Files.writeString(key, "corrupt-existing-key");
        assertThat(new CredentialCipher("", store()).available()).isFalse();
        assertThat(Files.readString(key)).isEqualTo("corrupt-existing-key");
    }

    @Test
    void explicitKeyHasPriorityAndDoesNotCreateAnAutomaticKey() {
        String configured = Base64.getEncoder().encodeToString(new byte[32]);
        assertThat(new CredentialCipher(configured, store()).available()).isTrue();
        assertThat(temp.resolve("clawbot/master.key")).doesNotExist();
        assertThat(new CredentialCipher("invalid-explicit-key", store()).available())
                .isFalse();
        assertThat(temp.resolve("clawbot/master.key")).doesNotExist();
    }
}
