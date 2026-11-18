package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliArchivesTest {
    @TempDir
    Path temp;

    @Test
    void extractsRegularFilesAndIgnoresArchiveLinks() throws Exception {
        Path archive = archive("package/safe.txt", "hello", '0');
        Path target = temp.resolve("out");
        CliArchives.tar(archive, "package/", target, null, 1024);
        assertThat(Files.readString(target.resolve("safe.txt"))).isEqualTo("hello");
        Path linked = archive("package/link", "", '2');
        assertThatThrownBy(() -> CliArchives.tar(linked, "package/", temp.resolve("links"), null, 1024))
                .hasMessage("CLI_ARCHIVE_INVALID");
        assertThat(temp.resolve("links/link")).doesNotExist();
    }

    @Test
    void rejectsTraversalAndExpandedSizeBeforeWriting() throws Exception {
        Path archive = archive("package/../../escaped", "bad", '0');
        assertThatThrownBy(() -> CliArchives.tar(archive, "package/", temp.resolve("out"), null, 1024))
                .hasMessage("CLI_ARCHIVE_INVALID");
        Path huge = archive("package/file", "too long", '0');
        assertThatThrownBy(() -> CliArchives.tar(huge, "package/", temp.resolve("large"), null, 2))
                .hasMessage("CLI_ARCHIVE_INVALID");
        assertThat(temp.resolve("escaped")).doesNotExist();
        assertThat(temp.resolve("large/file")).doesNotExist();
    }

    private Path archive(String name, String content, char type) throws Exception {
        Path archive = Files.createTempFile(temp, "fixture", ".tgz");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8), header = new byte[512];
        System.arraycopy(name.getBytes(StandardCharsets.UTF_8), 0, header, 0, name.length());
        byte[] size = String.format("%011o", bytes.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = (byte) type;
        try (var out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            out.write(header);
            out.write(bytes);
            out.write(new byte[(512 - bytes.length % 512) % 512]);
            out.write(new byte[1024]);
        }
        return archive;
    }
}
