package com.xhhao.clawbotnotify.assistant.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;

/** Bounded extraction into a new private staging directory; archive links are never followed. */
final class CliArchives {
    private CliArchives() {}

    static void tar(Path archive, String prefix, Path directory, String singleFile, long limit) throws Exception {
        long expanded = 0;
        int count = 0;
        boolean matched = false;
        String extendedName = null;
        try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                byte[] header = input.readNBytes(512);
                if (header.length == 512 && header[0] == 0) break;
                if (header.length != 512 || ++count > 30000) throw invalid();
                long size;
                try {
                    size = Long.parseLong(text(header, 124, 12).strip(), 8);
                } catch (NumberFormatException error) {
                    throw invalid();
                }
                if (size < 0 || (expanded += size) > limit) throw invalid();
                String name = text(header, 0, 100);
                String parent = text(header, 345, 155);
                if (!parent.isEmpty()) name = parent + "/" + name;
                char type = (char) header[156];
                if (type == 'L' || type == 'x') {
                    if (size > 1024 * 1024) throw invalid();
                    String metadata = new String(input.readNBytes((int) size), StandardCharsets.UTF_8);
                    if (type == 'L')
                        extendedName = metadata.replace("\u0000", "").strip();
                    else
                        for (String line : metadata.split("\n")) {
                            int start = line.indexOf(" path=");
                            if (start >= 0) extendedName = line.substring(start + 6);
                        }
                } else {
                    if (extendedName != null) {
                        name = extendedName;
                        extendedName = null;
                    }
                    if ((type == '0' || type == 0)
                            && name.startsWith(prefix)
                            && (singleFile == null || name.equals(singleFile))) {
                        String relative = singleFile == null ? name.substring(prefix.length()) : "node";
                        Path target = safePath(directory, relative);
                        Files.createDirectories(target.getParent());
                        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                            copy(input, output, size);
                        }
                        matched = true;
                        if (singleFile != null) return;
                    } else {
                        // Symlinks, hard links, device files and metadata have no installation role.
                        input.skipNBytes(size);
                    }
                }
                input.skipNBytes((512 - size % 512) % 512);
            }
        }
        if (!matched) throw invalid();
    }

    static Path safePath(Path directory, String relative) {
        if (relative.isEmpty()
                || relative.indexOf('\\') >= 0
                || relative.indexOf('\u0000') >= 0
                || relative.contains(":")
                || relative.startsWith("/")) throw invalid();
        Path root = directory.toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw invalid();
        return target;
    }

    private static String text(byte[] header, int offset, int length) {
        return new String(header, offset, length, StandardCharsets.UTF_8).split("\u0000", 2)[0];
    }

    private static void copy(InputStream input, java.io.OutputStream output, long size) throws Exception {
        byte[] block = new byte[65536];
        for (long remaining = size; remaining > 0; ) {
            int count = input.read(block, 0, (int) Math.min(block.length, remaining));
            if (count < 0) throw invalid();
            output.write(block, 0, count);
            remaining -= count;
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("CLI_ARCHIVE_INVALID");
    }
}
