package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "CLAWBOT_SETUP_INTEGRATION", matches = "true")
class NativeCliSetupIntegrationTest {
    @TempDir
    Path root;

    @Test
    void downloadsVerifiedOfficialRuntimeAndCliAndRunsWithoutNpmOrKeyring() throws Exception {
        var nodes = new NodeRuntimeInstaller();
        String platform = NodeRuntimeInstaller.platform(System.getProperty("os.name"), System.getProperty("os.arch"));
        Path node = nodes.install(root, platform, ignored -> {});
        var cli = new CliDistributionInstaller();
        Path entry = cli.install(root, ignored -> {});
        CliProcessCheck.cli(node, entry);
        assertThat(CliProcessCheck.nodeVersion(node)).isEqualTo(NodeRuntimeInstaller.VERSION);
        assertThat(cli.cached(root)).isEqualTo(entry);
        long modified = Files.getLastModifiedTime(entry).toMillis();
        assertThat(cli.install(root, ignored -> {})).isEqualTo(entry);
        assertThat(Files.getLastModifiedTime(entry).toMillis()).isEqualTo(modified);
        // The real CLI uses the managed stdin header even for help.
        var builder = new ProcessBuilder(node.toString(), entry.toString(), "--help");
        builder.environment().clear();
        builder.environment().put("PATH", node.getParent().toString());
        var child = builder.start();
        child.getOutputStream().write("{}\n".getBytes());
        child.getOutputStream().close();
        assertThat(child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isZero();
        assertThat(new String(child.getInputStream().readAllBytes())).contains("post", "comment", "shop", "backup");
        // Corrupt a dependency: its packaged expected digest must detect it.
        Files.writeString(entry.getParent().resolve("node_modules/cac/dist/index.js"), "tampered");
        assertThat(cli.cached(root)).isNull();
        Path repaired = cli.install(root, ignored -> {});
        CliProcessCheck.cli(node, repaired);
    }
}
