package com.xhhao.clawbotnotify.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Plugin data lives beside Halo's database, outside replaceable plugin files. */
@Component
public class ClawBotDataDirectory {
    private final Path root;

    @org.springframework.beans.factory.annotation.Autowired
    public ClawBotDataDirectory(
            org.springframework.context.ApplicationContext context,
            @Value("${clawbot.data-dir:${CLAWBOT_DATA_DIR:}}") String override) {
        this(workDirectory(context), override);
    }

    private static String workDirectory(org.springframework.context.ApplicationContext context) {
        // Plugin environments do not necessarily inherit the host's application.yaml.
        while (context.getParent() != null) context = context.getParent();
        return context.getEnvironment().getProperty("halo.work-dir", System.getProperty("user.home") + "/.halo2");
    }

    public ClawBotDataDirectory(String workDir, String override) {
        root = (override == null || override.isBlank() ? Path.of(workDir).resolve("clawbot") : Path.of(override))
                .toAbsolutePath()
                .normalize();
    }

    public Path root() {
        return root;
    }

    public static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        privatePermissions(path, true);
    }

    public static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        var acl = Files.getFileAttributeView(path, java.nio.file.attribute.AclFileAttributeView.class);
        if (acl != null) {
            var entry = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(path))
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class));
            if (directory)
                entry.setFlags(
                        java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                        java.nio.file.attribute.AclEntryFlag.FILE_INHERIT);
            acl.setAcl(java.util.List.of(entry.build()));
            return;
        }
        throw new IOException("PRIVATE_FILE_PERMISSIONS_UNAVAILABLE");
    }
}
