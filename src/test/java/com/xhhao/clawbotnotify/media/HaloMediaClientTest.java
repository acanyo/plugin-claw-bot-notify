package com.xhhao.clawbotnotify.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloMediaClientTest {
    DisposableServer server;
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    HaloMediaClient client(int status, String response) throws Exception {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, reply) -> {
                    calls.incrementAndGet();
                    path.set(request.uri());
                    assertThat(request.requestHeaders().get("Authorization")).isEqualTo("Bearer test-private-token");
                    return request.receive()
                            .aggregate()
                            .asString()
                            .defaultIfEmpty("")
                            .flatMap(text -> {
                                body.set(text);
                                reply.status(status).header("Content-Type", "application/json");
                                if (status == 302)
                                    reply.header("Location", "http://127.0.0.1:" + server.port() + "/redirect");
                                return reply.sendString(Mono.just(response)).then();
                            });
                })
                .bindNow();
        var url = mock(ExternalUrlSupplier.class);
        when(url.getRaw())
                .thenReturn(URI.create("http://127.0.0.1:" + server.port()).toURL());
        return new HaloMediaClient(url, WebClient.builder().build());
    }

    @Test
    void multipartUploadUsesExactBytesAndResolvesPermalink() throws Exception {
        var client = client(
                200, "{\"metadata\":{\"name\":\"attachment\"},\"status\":{\"permalink\":\"/upload/photo.png\"}}");
        var image = new MediaFileStore.Image(
                "id", "photo.png", "image/png", 5, "hash", Instant.now().plusSeconds(100));
        var attachment =
                client.upload("test-private-token", image, "bytes".getBytes()).block();
        assertThat(path.get()).isEqualTo("/apis/console.api.storage.halo.run/v1alpha1/attachments/-/upload");
        assertThat(body.get()).contains("name=\"file\"", "filename=\"photo.png\"", "Content-Type: image/png", "bytes");
        assertThat(attachment.name()).isEqualTo("attachment");
        assertThat(attachment.url()).isEqualTo("http://127.0.0.1:" + server.port() + "/upload/photo.png");
    }

    @Test
    void momentUsesRealMediumShapeAndEscapesHtml() throws Exception {
        var client = client(200, "{\"metadata\":{\"name\":\"moment\"}}");
        client.moment(
                        "test-private-token",
                        "moment",
                        "clawbot",
                        "正文\n<script>bad()</script>",
                        "PRIVATE",
                        List.of(new HaloMediaClient.Attachment("attachment", "https://site/image.png", "image/png")))
                .block();
        assertThat(path.get()).isEqualTo("/apis/moment.halo.run/v1alpha1/moments");
        var spec = json.readTree(body.get()).path("spec");
        assertThat(spec.path("owner").asText()).isEqualTo("clawbot");
        assertThat(spec.path("visible").asText()).isEqualTo("PRIVATE");
        assertThat(spec.path("content").path("medium").get(0).path("type").asText())
                .isEqualTo("PHOTO");
        assertThat(spec.path("content").path("medium").get(0).path("url").asText())
                .isEqualTo("https://site/image.png");
        assertThat(spec.path("content").path("html").asText())
                .contains("&lt;script&gt;")
                .doesNotContain("<script>");
    }

    @Test
    void coverUpdateRetainsResourceVersionAndAllOtherFields() throws Exception {
        var client = client(200, "{\"metadata\":{\"name\":\"post\"}}");
        var snapshot = json.readTree(
                "{\"metadata\":{\"name\":\"post\",\"version\":7},\"spec\":{\"cover\":\"old\",\"title\":\"标题\",\"releaseSnapshot\":\"snapshot\"}}");
        client.setCover("test-private-token", snapshot, "https://site/new.png").block();
        var sent = json.readTree(body.get());
        assertThat(path.get()).isEqualTo("/apis/content.halo.run/v1alpha1/posts/post");
        assertThat(sent.path("metadata").path("version").asLong()).isEqualTo(7);
        assertThat(sent.path("spec").path("cover").asText()).isEqualTo("https://site/new.png");
        assertThat(sent.path("spec").path("releaseSnapshot").asText()).isEqualTo("snapshot");
        assertThat(snapshot.path("spec").path("cover").asText()).isEqualTo("old");
    }

    @Test
    void forbiddenOrRedirectResponseIsNotRetriedAndDoesNotExposeBody() throws Exception {
        var forbiddenClient = client(403, "private-token-in-error");
        assertThatThrownBy(
                        () -> forbiddenClient.post("test-private-token", "post").block())
                .hasMessage("MEDIA_PERMISSION_DENIED");
        assertThat(calls).hasValue(1);
        server.disposeNow();
        var redirectClient = client(302, "private-token-in-error");
        assertThatThrownBy(
                        () -> redirectClient.post("test-private-token", "post").block())
                .hasMessage("MEDIA_REQUEST_FAILED");
        assertThat(calls).hasValue(2);
    }
}
