package com.xhhao.clawbotnotify.assistant.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Checks the Halo process's environment, never the browser's operating system. */
public class CliEnvironmentDetector {
    public CliEnvironment detect(Path root, NodeRuntimeInstaller nodes, CliDistributionInstaller cli) throws Exception {
        String system = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        String platform = null;
        try {
            platform = NodeRuntimeInstaller.platform(system, arch);
        } catch (IllegalStateException ignored) {
            /* Custom Node.js can still run the portable JS CLI. */
        }
        boolean musl = isMusl(system);
        boolean supported = platform != null && !musl;
        boolean writable = storageWritable(root);
        String configured = System.getenv("CLAWBOT_NODE_EXECUTABLE");
        String source = "MISSING", version = null;
        Path node = null;
        if (configured != null && !configured.isBlank()) {
            source = "CUSTOM";
            try {
                node = Path.of(configured);
                version = CliProcessCheck.nodeVersion(node);
            } catch (RuntimeException ignored) {
                /* Explicit invalid overrides are not silently replaced. */
            }
        } else {
            if (platform != null) node = nodes.cached(root, platform);
            if (node != null) {
                source = "MANAGED";
                version = CliProcessCheck.nodeVersion(node);
            }
            if (version == null) {
                Path systemNode = systemNode(system);
                if (systemNode != null) {
                    String found = CliProcessCheck.nodeVersion(systemNode);
                    if (found != null) {
                        node = systemNode;
                        source = "SYSTEM";
                        version = found;
                    }
                }
            }
        }
        boolean installed = cli.cached(root) != null;
        String error = !writable
                ? "CLI_STORAGE_FAILED"
                : "CUSTOM".equals(source) && version == null
                        ? "CLI_RUNTIME_UNAVAILABLE"
                        : !supported && version == null
                                ? (musl ? "CLI_MUSL_UNSUPPORTED" : "CLI_PLATFORM_UNSUPPORTED")
                                : null;
        return new CliEnvironment(
                Instant.now(),
                system,
                arch,
                platform,
                Files.exists(Path.of("/.dockerenv")) || Files.exists(Path.of("/run/.containerenv")),
                supported,
                root.toString(),
                writable,
                source,
                version,
                node == null ? null : node.toString(),
                installed,
                error == null,
                error);
    }

    private static Path systemNode(String system) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String binary = system.toLowerCase(java.util.Locale.ROOT).contains("win") ? "node.exe" : "node";
        for (String directory : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (directory.isBlank()) continue;
            Path node = Path.of(directory).resolve(binary).toAbsolutePath().normalize();
            if (Files.isRegularFile(node) && Files.isExecutable(node)) return node;
        }
        return null;
    }

    private static boolean storageWritable(Path root) {
        try {
            Path parent = root;
            while (parent != null && !Files.exists(parent)) parent = parent.getParent();
            if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) return false;
            Path probe = Files.createTempFile(parent, ".clawbot-check-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isMusl(String system) {
        if (!system.toLowerCase(java.util.Locale.ROOT).contains("linux")) return false;
        if (Files.exists(Path.of("/etc/alpine-release"))) return true;
        try (var libraries = Files.list(Path.of("/lib"))) {
            return libraries.anyMatch(path -> path.getFileName().toString().startsWith("ld-musl-"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
