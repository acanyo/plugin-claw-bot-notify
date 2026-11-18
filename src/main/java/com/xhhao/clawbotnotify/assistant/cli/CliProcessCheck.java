package com.xhhao.clawbotnotify.assistant.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CliProcessCheck {
    private CliProcessCheck() {}

    static String nodeVersion(Path node) {
        try {
            String version = output(List.of(node.toString(), "--version"), false);
            if (!version.matches("v[0-9]{1,3}\\.[0-9]+\\.[0-9]+")
                    || Integer.parseInt(version.substring(1, version.indexOf('.'))) < 22) return null;
            return version.substring(1);
        } catch (Exception ignored) {
            return null;
        }
    }

    static void cli(Path node, Path cli) throws Exception {
        String version = output(List.of(node.toString(), cli.toString(), "--version"), true);
        if (!version.startsWith("halo/" + CliDistributionInstaller.VERSION + " ")
                && !version.equals("halo/" + CliDistributionInstaller.VERSION))
            throw new IllegalStateException("CLI_INSTALLATION_FAILED");
    }

    private static String output(List<String> command, boolean header) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        for (String name : List.of("PATH", "SystemRoot", "WINDIR", "LANG", "LC_ALL", "TMPDIR")) {
            String value = System.getenv(name);
            if (value != null) builder.environment().put(name, value);
        }
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                if (header) input.write("{}\n".getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException("CLI_RUNTIME_UNAVAILABLE");
            return new String(process.getInputStream().readNBytes(512), StandardCharsets.UTF_8).strip();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
