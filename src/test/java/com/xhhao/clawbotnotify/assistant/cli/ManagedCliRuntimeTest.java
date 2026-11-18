package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedCliRuntimeTest {
    @TempDir
    Path temp;

    final NodeRuntimeInstaller nodes = mock(NodeRuntimeInstaller.class);
    final CliDistributionInstaller cli = mock(CliDistributionInstaller.class);
    final CliEnvironmentDetector detector = mock(CliEnvironmentDetector.class);

    @Test
    void startupDetectionAndCommandInvocationNeverDownloadWithoutAnExplicitInstall() throws Exception {
        when(detector.detect(any(), any(), any())).thenReturn(environment(false));
        var runtime = runtime();
        runtime.start();
        await(runtime, "REQUIRED");
        assertThatThrownBy(() -> runtime.command().block()).hasMessage("CLI_SETUP_REQUIRED");
        runtime.detect();
        await(runtime, "REQUIRED");
        verify(nodes, never()).install(any(), any(), any());
        verify(cli, never()).install(any(), any());
        assertThat(runtime.status().environment().system()).isEqualTo("Linux");
    }

    @Test
    void repeatedInstallClicksShareOneDownloadAndRestartReusesVerifiedTools() throws Exception {
        Path node = script();
        Path entry = Files.writeString(temp.resolve("cli.mjs"), "test");
        when(detector.detect(any(), any(), any())).thenReturn(environment(false), environment(true));
        when(nodes.install(any(), eq("linux-arm64"), any())).thenAnswer(call -> {
            Thread.sleep(60);
            return node;
        });
        when(cli.install(any(), any())).thenReturn(entry);
        when(cli.cached(any())).thenReturn(entry);
        var runtime = runtime();
        runtime.install();
        runtime.install();
        runtime.install();
        await(runtime, "READY");
        assertThat(runtime.command().block()).containsExactly(node.toString(), entry.toString());
        verify(nodes, times(1)).install(any(), any(), any());
        verify(cli, times(1)).install(any(), any());
        var restarted = runtime();
        restarted.start();
        await(restarted, "READY");
        verify(nodes, times(1)).install(any(), any(), any());
        verify(cli, times(1)).install(any(), any());
    }

    @Test
    void installFailureIsSafeAndCanBeRetriedExplicitly() throws Exception {
        Path node = script();
        Path entry = Files.writeString(temp.resolve("cli.mjs"), "test");
        when(detector.detect(any(), any(), any())).thenReturn(environment(false));
        when(nodes.install(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("secret-proxy-password"))
                .thenReturn(node);
        when(cli.install(any(), any())).thenReturn(entry);
        var runtime = runtime();
        runtime.install();
        await(runtime, "FAILED");
        assertThat(runtime.status().toString()).doesNotContain("secret-proxy-password");
        assertThat(runtime.status().errorCode()).isEqualTo("CLI_PREPARATION_FAILED");
        runtime.retry();
        await(runtime, "READY");
    }

    @Test
    void explicitExternalModeRetainsItsCommandAndDoesNotRunAnInstaller() {
        var runtime = new ManagedCliRuntime(temp, "/custom/halo", nodes, cli, detector);
        runtime.start();
        runtime.install();
        assertThat(runtime.command().block()).containsExactly("/custom/halo");
        verifyNoInteractions(nodes, cli, detector);
    }

    @Test
    void detectsPlatformAliasesAndRejectsUnsupportedArchitectures() {
        assertThat(NodeRuntimeInstaller.platform("Linux", "aarch64")).isEqualTo("linux-arm64");
        assertThat(NodeRuntimeInstaller.platform("Mac OS X", "x86_64")).isEqualTo("darwin-x64");
        assertThat(NodeRuntimeInstaller.platform("Windows 11", "amd64")).isEqualTo("win-x64");
        assertThatThrownBy(() -> NodeRuntimeInstaller.platform("Linux", "riscv64"))
                .hasMessage("CLI_PLATFORM_UNSUPPORTED");
    }

    private ManagedCliRuntime runtime() {
        return new ManagedCliRuntime(temp, null, nodes, cli, detector);
    }

    private CliEnvironment environment(boolean installed) {
        return new CliEnvironment(
                Instant.now(),
                "Linux",
                "aarch64",
                "linux-arm64",
                true,
                true,
                temp.toString(),
                true,
                installed ? "MANAGED" : "MISSING",
                installed ? "22.23.5" : null,
                installed ? temp.resolve("node").toString() : null,
                installed,
                true,
                null);
    }

    private Path script() throws Exception {
        Path node = Files.writeString(
                temp.resolve("node"),
                "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo v22.23.5; else echo halo/1.4.0; fi\n");
        node.toFile().setExecutable(true);
        return node;
    }

    private static void await(ManagedCliRuntime runtime, String state) throws Exception {
        for (int i = 0; i < 300 && !runtime.status().state().equals(state); i++) Thread.sleep(10);
        assertThat(runtime.status().state()).isEqualTo(state);
        Thread.sleep(15); // Allow the asynchronous operation to release its single-flight guard.
    }
}
