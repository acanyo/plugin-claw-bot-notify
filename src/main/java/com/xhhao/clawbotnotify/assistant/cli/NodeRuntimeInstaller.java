package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.storage.ClawBotDataDirectory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

/** Downloads one pinned official prebuilt executable, only after explicit installation. */
public class NodeRuntimeInstaller {
    public static final String VERSION = "22.23.5";
    private static final long MAX_BINARY = 220L * 1024 * 1024;
    private final CliDownload download;
    private final JsonNode artifacts;

    public NodeRuntimeInstaller() {
        this(new CliDownload());
    }

    NodeRuntimeInstaller(CliDownload download) {
        this(download, loadArtifacts());
    }

    NodeRuntimeInstaller(CliDownload download, JsonNode artifacts) {
        this.download = download;
        this.artifacts = artifacts;
    }

    private static JsonNode loadArtifacts() {
        try (var input = NodeRuntimeInstaller.class.getResourceAsStream("/managed-cli/runtimes.json")) {
            return new ObjectMapper().readTree(input).path("artifacts");
        } catch (Exception error) {
            throw new IllegalStateException("CLI_BUNDLE_MISSING");
        }
    }

    public static String platform(String os, String architecture) {
        String system = os.toLowerCase(java.util.Locale.ROOT);
        String cpu =
                switch (architecture.toLowerCase(java.util.Locale.ROOT)) {
                    case "amd64", "x86_64", "x64" -> "x64";
                    case "aarch64", "arm64" -> "arm64";
                    default -> throw new IllegalStateException("CLI_PLATFORM_UNSUPPORTED");
                };
        String family = system.contains("mac") || system.contains("darwin")
                ? "darwin"
                : system.contains("win") ? "win" : system.contains("linux") ? "linux" : "unknown";
        if (family.equals("unknown")) throw new IllegalStateException("CLI_PLATFORM_UNSUPPORTED");
        return family + "-" + cpu;
    }

    public Path cached(Path root, String platform) throws Exception {
        JsonNode artifact = artifact(platform);
        Path target = executable(root, platform);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) == 0
                || Files.size(target) > MAX_BINARY
                || !digest(target).equals(artifact.path("binarySha256").asText())) return null;
        return target;
    }

    public Path install(Path root, String platform, Consumer<CliSetupProgress> progress) throws Exception {
        Path cached = cached(root, platform);
        if (cached != null) {
            if (!Files.isExecutable(cached) && !cached.toFile().setExecutable(true, true))
                throw new IllegalStateException("CLI_RUNTIME_PERMISSION");
            return cached;
        }
        JsonNode artifact = artifact(platform);
        ClawBotDataDirectory.directory(root);
        Path archive = Files.createTempFile(root, "node-download-", ".tmp");
        Path staging = Files.createTempDirectory(root, "node-install-");
        ClawBotDataDirectory.privatePermissions(staging, true);
        Path pending = staging.resolve("node");
        try {
            download.download(
                    java.net.URI.create("https://nodejs.org/dist/v" + VERSION + "/"
                            + artifact.path("file").asText()),
                    archive,
                    150L * 1024 * 1024,
                    (bytes, total) -> progress.accept(new CliSetupProgress("DOWNLOADING_NODE", bytes, total, 0, 0)));
            progress.accept(CliSetupProgress.stage("VERIFYING_NODE"));
            if (!digest(archive).equals(artifact.path("sha256").asText()))
                throw new IllegalStateException("CLI_DOWNLOAD_CHECKSUM");
            progress.accept(CliSetupProgress.stage("INSTALLING_NODE"));
            if (platform.startsWith("win-")) Files.move(archive, pending);
            else {
                String prefix = "node-v" + VERSION + "-" + platform + "/";
                CliArchives.tar(archive, prefix, staging, prefix + "bin/node", 1024L * 1024 * 1024);
            }
            if (Files.size(pending) > MAX_BINARY
                    || !digest(pending).equals(artifact.path("binarySha256").asText()))
                throw new IllegalStateException("CLI_DOWNLOAD_CHECKSUM");
            ClawBotDataDirectory.privatePermissions(pending, false);
            if (!pending.toFile().setExecutable(true, true)) throw new IllegalStateException("CLI_RUNTIME_PERMISSION");
            Path target = executable(root, platform);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(pending);
            Files.deleteIfExists(staging);
        }
    }

    private JsonNode artifact(String platform) {
        JsonNode value = artifacts.path(platform);
        if (!value.isObject()) throw new IllegalStateException("CLI_PLATFORM_UNSUPPORTED");
        return value;
    }

    private Path executable(Path root, String platform) {
        return root.resolve("node-v" + VERSION + "-" + platform + (platform.startsWith("win-") ? ".exe" : ""));
    }

    static String digest(Path path) throws Exception {
        return digest(path, "SHA-256");
    }

    static String digest(Path path, String algorithm) throws Exception {
        var hash = MessageDigest.getInstance(algorithm);
        try (var input = Files.newInputStream(path)) {
            byte[] block = new byte[65536];
            for (int size; (size = input.read(block)) >= 0; ) hash.update(block, 0, size);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
