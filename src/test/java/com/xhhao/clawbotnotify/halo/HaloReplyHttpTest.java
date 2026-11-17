package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloReplyHttpTest {
    DisposableServer server;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> method = new AtomicReference<>();

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    HaloIdentityVerifier client(int status, String response) throws Exception {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, reply) -> {
                    calls.incrementAndGet();
                    path.set(request.uri());
                    auth.set(request.requestHeaders().get("Authorization"));
                    method.set(request.method().name());
                    return request.receive().aggregate().asString().flatMap(value -> {
                        body.set(value);
                        reply.status(status).header("Content-Type", "application/json");
                        if (status == 302)
                            reply.header("Location", "http://127.0.0.1:" + server.port() + "/redirected");
                        return reply.sendString(Mono.just(response)).then();
                    });
                })
                .bindNow();
        var url = mock(ExternalUrlSupplier.class);
        when(url.getRaw())
                .thenReturn(URI.create("http://127.0.0.1:" + server.port() + "/site/")
                        .toURL());
        return new HaloIdentityVerifier(url);
    }

    @Test
    void postsRawAndSafeHtmlThroughOwnerPatWithoutOverridingIdentity() throws Exception {
        client(200, "{\"metadata\":{\"name\":\"reply\"}}")
                .postReply("private-pat", "comment-a", "**正文**", "<p><strong>正文</strong></p>")
                .block();
        assertThat(path.get()).isEqualTo("/site/apis/api.console.halo.run/v1alpha1/comments/comment-a/reply");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(auth.get()).isEqualTo("Bearer private-pat");
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("raw").asText()).isEqualTo("**正文**");
        assertThat(sent.path("content").asText()).contains("<strong>正文</strong>");
        assertThat(sent.has("owner")).isFalse();
        assertThat(sent.path("hidden").asBoolean()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void explicitRejectionsAreSafeAndDoNotEchoServerBody(int status) throws Exception {
        var client = client(status, "private-pat server-internals");
        assertThatThrownBy(() -> client.postReply("private-pat", "c", "r", "h").block())
                .isInstanceOfSatisfying(
                        HaloWriteException.class,
                        error -> assertThat(error.rejected()).isTrue())
                .hasMessageNotContaining("private-pat")
                .hasNoCause();
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 500})
    void uncertainHttpResultCannotTriggerBlindReplayOrRedirect(int status) throws Exception {
        var client = client(status, "private-pat");
        assertThatThrownBy(() -> client.postReply("private-pat", "c", "r", "h").block())
                .isInstanceOfSatisfying(
                        HaloWriteException.class,
                        error -> assertThat(error.rejected()).isFalse())
                .hasMessageNotContaining("private-pat")
                .hasNoCause();
        assertThat(calls).hasValue(1);
        assertThat(path.get()).doesNotContain("redirected");
    }

    @Test
    void nestedReplyIncludesQuoteTargetWithoutOverridingOwner() throws Exception {
        client(200, "{\"metadata\":{\"name\":\"nested-answer\"}}")
                .postReply("private-pat", "root", "visitor-reply", "正文", "<p>正文</p>")
                .block();
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(path.get()).endsWith("/comments/root/reply");
        assertThat(sent.path("quoteReply").asText()).isEqualTo("visitor-reply");
        assertThat(sent.has("owner")).isFalse();
        assertThat(auth.get()).isEqualTo("Bearer private-pat");
    }
}
