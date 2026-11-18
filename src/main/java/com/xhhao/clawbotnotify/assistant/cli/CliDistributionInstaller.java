package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.storage.ClawBotDataDirectory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.function.Consumer;

/** Installs the official CLI's locked JS dependencies without npm or lifecycle scripts. */
public class CliDistributionInstaller {
    public static final String VERSION = "1.4.0";
    private final JsonNode distribution;
    private final String identity;
    private final CliDownload download;

    public CliDistributionInstaller() {
        this(new CliDownload());
    }

    CliDistributionInstaller(CliDownload download) {
        this.download = download;
        try {
            byte[] bytes = resource("distribution.json");
            distribution = new ObjectMapper().readTree(bytes);
            identity = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .substring(0, 16);
            if (!VERSION.equals(distribution.path("version").asText())) throw new IllegalStateException();
        } catch (Exception error) {
            throw new IllegalStateException("CLI_BUNDLE_INVALID");
        }
    }

    public Path cached(Path root) throws Exception {
        Path directory = destination(root);
        if (!Files.isRegularFile(directory.resolve(".complete"), LinkOption.NOFOLLOW_LINKS)) return null;
        if (!matches(directory)) return null;
        for (String name : new String[] {"adapter.mjs", "keyring.mjs"}) {
            Path path = directory.resolve(name);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Arrays.equals(Files.readAllBytes(path), resource(name))) return null;
        }
        Path cli = directory.resolve("cli.mjs");
        if (!Files.isRegularFile(cli, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(cli).equals(adapt(directory))) return null;
        return cli;
    }

    public Path install(Path root, Consumer<CliSetupProgress> progress) throws Exception {
        Path cached = cached(root);
        if (cached != null) return cached;
        Path archives = root.resolve("downloads");
        ClawBotDataDirectory.directory(archives);
        Path staging = Files.createTempDirectory(root, "cli-install-");
        ClawBotDataDirectory.privatePermissions(staging, true);
        int total = distribution.path("packages").size();
        int completed = 0;
        try {
            for (var pkg : distribution.path("packages")) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String integrity = pkg.path("integrity").asText();
                if (!integrity.startsWith("sha512-")) throw new IllegalStateException("CLI_BUNDLE_INVALID");
                String expected = HexFormat.of().formatHex(Base64.getDecoder().decode(integrity.substring(7)));
                Path archive = archives.resolve(expected + ".tgz");
                final int done = completed;
                progress.accept(new CliSetupProgress("DOWNLOADING_CLI", 0, 0, done, total));
                if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                        || !NodeRuntimeInstaller.digest(archive, "SHA-512").equals(expected)) {
                    Path pending = Files.createTempFile(archives, "package-", ".tmp");
                    try {
                        var uri = java.net.URI.create(pkg.path("url").asText());
                        if (!"registry.npmjs.org".equals(uri.getHost()))
                            throw new IllegalStateException("CLI_BUNDLE_INVALID");
                        download.download(
                                uri,
                                pending,
                                30L * 1024 * 1024,
                                (bytes, size) -> progress.accept(
                                        new CliSetupProgress("DOWNLOADING_CLI", bytes, size, done, total)));
                        if (!NodeRuntimeInstaller.digest(pending, "SHA-512").equals(expected))
                            throw new IllegalStateException("CLI_DOWNLOAD_CHECKSUM");
                        Files.move(
                                pending, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(pending);
                    }
                }
                String packagePath = pkg.path("path").asText();
                if (!packagePath.startsWith("node_modules/")) throw new IllegalStateException("CLI_BUNDLE_INVALID");
                CliArchives.tar(
                        archive, "package/", CliArchives.safePath(staging, packagePath), null, 128L * 1024 * 1024);
                progress.accept(new CliSetupProgress("DOWNLOADING_CLI", 0, 0, ++completed, total));
            }
            progress.accept(CliSetupProgress.stage("VERIFYING_CLI"));
            if (!matches(staging)) throw new IllegalStateException("CLI_DOWNLOAD_CHECKSUM");
            for (String name : new String[] {
                "adapter.mjs", "keyring.mjs", "HALO-CLI-LICENSE", "NODE-LICENSE", "THIRD-PARTY-NOTICES.txt"
            }) Files.write(staging.resolve(name), resource(name));
            Files.writeString(staging.resolve("cli.mjs"), adapt(staging));
            Files.writeString(staging.resolve(".complete"), identity);
            Path target = destination(root);
            deleteTree(target);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target.resolve("cli.mjs");
        } finally {
            deleteTree(staging);
        }
    }

    private boolean matches(Path directory) throws Exception {
        var files = distribution.path("files").fields();
        while (files.hasNext()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var entry = files.next();
            Path path = CliArchives.safePath(directory, entry.getKey());
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !NodeRuntimeInstaller.digest(path)
                            .equals(entry.getValue().asText())) return false;
        }
        return true;
    }

    private static String adapt(Path directory) throws Exception {
        String original = Files.readString(directory.resolve("node_modules/@halo-dev/cli/dist/cli.mjs"));
        String constructor = "constructor(configStore = new ConfigStore())";
        String keyring = "import { Entry } from \"@napi-rs/keyring\";";
        if (original.indexOf(constructor) < 0
                || original.indexOf(constructor) != original.lastIndexOf(constructor)
                || original.indexOf(keyring) < 0
                || original.indexOf(keyring) != original.lastIndexOf(keyring))
            throw new IllegalStateException("CLI_DISTRIBUTION_INCOMPATIBLE");
        return "import { ManagedConfigStore } from './adapter.mjs';\n"
                + original.replaceFirst("^#![^\\n]*\\n", "")
                        .replace(constructor, "constructor(configStore = new ManagedConfigStore())")
                        .replace(keyring, "import { Entry } from './keyring.mjs';");
    }

    private Path destination(Path root) {
        return root.resolve("halo-" + VERSION + "-" + identity);
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = CliDistributionInstaller.class.getResourceAsStream("/managed-cli/" + name)) {
            if (input == null) throw new IllegalStateException("CLI_BUNDLE_MISSING");
            return input.readAllBytes();
        }
    }

    static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
