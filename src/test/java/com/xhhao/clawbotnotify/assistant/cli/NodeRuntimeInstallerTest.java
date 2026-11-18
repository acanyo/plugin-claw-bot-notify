package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeRuntimeInstallerTest {
    @TempDir
    Path temp;

    @Test
    void downloadsOnlyTheSelectedRuntimeAndRepairsCacheWithoutTrustingTheLegacySidecar() throws Exception {
        Path binary = Files.writeString(temp.resolve("fixture.exe"), "verified-test-binary");
        String hash = NodeRuntimeInstaller.digest(binary);
        var artifacts = new ObjectMapper().createObjectNode();
        artifacts
                .putObject("win-x64")
                .put("file", "win-x64/node.exe")
                .put("sha256", hash)
                .put("binarySha256", hash);
        var download = mock(CliDownload.class);
        doAnswer(call -> {
                    assertThat(call.getArgument(0).toString())
                            .isEqualTo("https://nodejs.org/dist/v22.23.5/win-x64/node.exe");
                    Files.copy(binary, (Path) call.getArgument(1), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return null;
                })
                .when(download)
                .download(any(), any(), anyLong(), any());
        var installer = new NodeRuntimeInstaller(download, artifacts);
        Path root = Files.createDirectory(temp.resolve("runtime"));
        var progress = new ArrayList<CliSetupProgress>();
        Path node = installer.install(root, "win-x64", progress::add);
        assertThat(Files.readString(node)).isEqualTo("verified-test-binary");
        node.toFile().setExecutable(false, false);
        assertThat(installer.install(root, "win-x64", progress::add)).isEqualTo(node);
        assertThat(Files.isExecutable(node)).isTrue();
        verify(download, times(1)).download(any(), any(), anyLong(), any());
        Files.writeString(node, "tampered");
        Files.writeString(root.resolve("node-v22.23.5-win-x64.sha256"), NodeRuntimeInstaller.digest(node));
        installer.install(root, "win-x64", progress::add);
        assertThat(Files.readString(node)).isEqualTo("verified-test-binary");
        verify(download, times(2)).download(any(), any(), anyLong(), any());
    }

    @Test
    void checksumFailureCleansStagingAndNeverInstallsTheDownloadedFile() throws Exception {
        var download = mock(CliDownload.class);
        doAnswer(call -> {
                    Files.writeString((Path) call.getArgument(1), "tampered");
                    return null;
                })
                .when(download)
                .download(any(), any(), anyLong(), any());
        var installer = new NodeRuntimeInstaller(download);
        assertThatThrownBy(() -> installer.install(temp, "linux-arm64", ignored -> {}))
                .hasMessage("CLI_DOWNLOAD_CHECKSUM");
        assertThat(temp.toFile().list()).isEmpty();
    }

    @Test
    void archiveExtractionRejectsTraversalAndDoesNotFollowLinks() throws Exception {
        assertThatThrownBy(() -> CliArchives.safePath(temp, "../../outside")).hasMessage("CLI_ARCHIVE_INVALID");
        assertThatThrownBy(() -> CliArchives.safePath(temp, "/outside")).hasMessage("CLI_ARCHIVE_INVALID");
        assertThatThrownBy(() -> CliArchives.safePath(temp, "a\\..\\outside")).hasMessage("CLI_ARCHIVE_INVALID");
    }
}
