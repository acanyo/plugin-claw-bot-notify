package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloIdentityVerifierTest {
    private final ExternalUrlSupplier externalUrl = mock(ExternalUrlSupplier.class);
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private DisposableServer server;

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    private HaloIdentityVerifier verifier(String response, int status) throws Exception {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, reply) -> {
                    calls.incrementAndGet();
                    requestPath.set(request.uri());
                    authorization.set(request.requestHeaders().get("Authorization"));
                    reply.status(status).header("Content-Type", "application/json");
                    if (status == 302) {
                        reply.header("Location", "http://127.0.0.1:" + server.port() + "/redirected");
                    }
                    return reply.sendString(Mono.just(response));
                })
                .bindNow();
        // This is Halo's default behavior with relative permalinks enabled.
        org.mockito.Mockito.lenient().when(externalUrl.get()).thenReturn(URI.create("/"));
        when(externalUrl.getRaw())
                .thenReturn(URI.create("http://127.0.0.1:" + server.port() + "/site/")
                        .toURL());
        return new HaloIdentityVerifier(externalUrl);
    }

    @Test
    void verifiesAgainstConfiguredAddressWhenPermalinkIsRelative() throws Exception {
        var verifier = verifier("{\"user\":{\"metadata\":{\"name\":\"admin\"}}}", 200);
        assertThatCode(() -> verifier.verify("test-pat", "admin").block()).doesNotThrowAnyException();
        assertThat(requestPath.get()).isEqualTo("/site/apis/api.console.halo.run/v1alpha1/users/-");
        assertThat(authorization.get()).isEqualTo("Bearer test-pat");
    }

    @Test
    void rejectsDifferentIdentity() throws Exception {
        var verifier = verifier("{\"user\":{\"metadata\":{\"name\":\"another-user\"}}}", 200);
        assertThatThrownBy(() -> verifier.verify("test-pat", "admin").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HALO_IDENTITY_MISMATCH");
    }

    @Test
    void doesNotForwardTokenThroughRedirect() throws Exception {
        var verifier = verifier("", 302);
        assertThatThrownBy(() -> verifier.verify("test-pat", "admin").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HALO_AUTH_FAILED");
        assertThat(calls).hasValue(1);
        assertThat(requestPath.get()).doesNotContain("redirected");
    }

    @Test
    void missingConfiguredAddressIsRejected() {
        when(externalUrl.getRaw()).thenReturn(null);
        var verifier = new HaloIdentityVerifier(externalUrl);
        assertThatThrownBy(() -> verifier.verify("test-pat", "admin").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HALO_ADDRESS_INVALID");
    }
}
