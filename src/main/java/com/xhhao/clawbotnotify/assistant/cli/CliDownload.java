package com.xhhao.clawbotnotify.assistant.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BiConsumer;

/** Fixed public distribution hosts only. No credentials, redirects or install scripts. */
public class CliDownload {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public void download(URI uri, Path target, long limit, BiConsumer<Long, Long> progress) throws Exception {
        if (!"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !("nodejs.org".equals(uri.getHost()) || "registry.npmjs.org".equals(uri.getHost())))
            throw new IllegalStateException("CLI_DOWNLOAD_SOURCE_INVALID");
        var response = client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(4)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body();
                var output = Files.newOutputStream(target)) {
            long expected =
                    response.headers().firstValueAsLong("Content-Length").orElse(0);
            if (response.statusCode() != 200 || expected > limit)
                throw new IllegalStateException("CLI_DOWNLOAD_FAILED");
            progress.accept(0L, expected);
            byte[] block = new byte[65536];
            long total = 0;
            for (int size; (size = input.read(block)) >= 0; ) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                total += size;
                if (total > limit) throw new IllegalStateException("CLI_DOWNLOAD_FAILED");
                output.write(block, 0, size);
                progress.accept(total, expected);
            }
            if (total == 0 || (expected > 0 && total != expected))
                throw new IllegalStateException("CLI_DOWNLOAD_FAILED");
        }
    }
}
