package com.xhhao.clawbotnotify.credential;

import com.xhhao.clawbotnotify.storage.ClawBotDataDirectory;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class MasterKeyStore {
    private final Path root;

    public MasterKeyStore(ClawBotDataDirectory data) {
        root = data.root();
    }

    // Synchronization handles plugin callers in one JVM; the file lock protects
    // parallel Halo processes sharing the same persistent directory.
    public String loadOrCreate() {
        synchronized (MasterKeyStore.class) {
            return lockedLoadOrCreate();
        }
    }

    private String lockedLoadOrCreate() {
        try {
            ClawBotDataDirectory.directory(root);
            Path lock = root.resolve("master-key.lock");
            try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var ignored = channel.lock()) {
                ClawBotDataDirectory.privatePermissions(lock, false);
                Path key = root.resolve("master.key");
                if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS) || Files.size(key) > 128)
                        throw new IllegalStateException("CREDENTIAL_KEY_INVALID");
                    ClawBotDataDirectory.privatePermissions(key, false);
                    String saved = Files.readString(key).strip();
                    if (Base64.getDecoder().decode(saved).length != 32)
                        throw new IllegalStateException("CREDENTIAL_KEY_INVALID");
                    return saved;
                }
                byte[] bytes = new byte[32];
                new SecureRandom().nextBytes(bytes);
                String encoded = Base64.getEncoder().encodeToString(bytes);
                Path pending = Files.createTempFile(root, "master-key-", ".tmp");
                try {
                    ClawBotDataDirectory.privatePermissions(pending, false);
                    Files.writeString(pending, encoded + "\n");
                    try (var out = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                        out.force(true);
                    }
                    Files.move(pending, key, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(pending);
                }
                return encoded;
            }
        } catch (Exception error) {
            // Never replace an unreadable or corrupt key: existing Secrets need it.
            throw new IllegalStateException("CREDENTIAL_KEY_UNAVAILABLE");
        }
    }
}
