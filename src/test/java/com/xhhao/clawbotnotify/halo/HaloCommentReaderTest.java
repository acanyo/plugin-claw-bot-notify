package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.model.WechatBinding;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Ref;
import run.halo.app.infra.ExternalUrlSupplier;

class HaloCommentReaderTest {
    private DisposableServer server;
    private final AtomicInteger commentReads = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> verb = new AtomicReference<>();
    private final AtomicReference<String> momentRequest = new AtomicReference<>();
    private final WechatBinding binding = new WechatBinding();
    private int momentStatus = 200;

    HaloCommentReaderTest() {
        var spec = new WechatBinding.Spec();
        spec.setHaloUsername("admin");
        spec.setEnabled(true);
        spec.setNotificationStartAt(Instant.parse("2026-11-13T00:00:00Z"));
        binding.setSpec(spec);
    }

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    private HaloCommentReader reader(boolean administrator, int commentStatus) throws Exception {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    authorization.set(request.requestHeaders().get("Authorization"));
                    verb.set(request.method().name());
                    response.header("Content-Type", "application/json");
                    if (request.uri().endsWith("users/-"))
                        return response.sendString(Mono.just(
                                "{\"user\":{\"metadata\":{\"name\":\"admin\"},\"spec\":{\"disabled\":false}},\"roles\":"
                                        + (administrator ? "[{\"metadata\":{\"name\":\"super-role\"}}]" : "[]") + "}"));
                    if (request.uri().startsWith("/apis/api.moment.halo.run/")) {
                        momentRequest.set(request.uri());
                        return response.status(momentStatus).sendString(Mono.just("""
                            {"spec":{"content":{"html":"<p>照片 &amp; 近况</p><script>ignored</script>"}}}
                            """));
                    }
                    commentReads.incrementAndGet();
                    return response.status(commentStatus).sendString(Mono.just("""
                {"apiVersion":"content.halo.run/v1alpha1","kind":"Comment","metadata":{"name":"a","creationTimestamp":"2026-11-13T01:00:00Z"},
                "spec":{"raw":"private body","content":"<p>private body</p>","approved":false,"hidden":true,"subjectRef":{"group":"content.halo.run","version":"v1alpha1","kind":"Post","name":"post"}}}
                """));
                })
                .bindNow();
        var external = mock(ExternalUrlSupplier.class);
        when(external.getRaw())
                .thenReturn(URI.create("http://127.0.0.1:" + server.port()).toURL());
        var roles = mock(run.halo.app.core.user.service.RoleService.class);
        when(roles.getRolesByUsername("admin"))
                .thenReturn(
                        administrator
                                ? reactor.core.publisher.Flux.just("super-role")
                                : reactor.core.publisher.Flux.empty());
        return new HaloCommentReader(new HaloIdentityVerifier(external), roles);
    }

    @Test
    void pendingAndHiddenCommentRequiresRealPatAuthorizedGet() throws Exception {
        var result = reader(true, 200).get(binding, "read-pat", "a").block();
        assertThat(result.getSpec().getRaw()).isEqualTo("private body");
        assertThat(result.getSpec().getHidden()).isTrue();
        assertThat(authorization.get()).isEqualTo("Bearer read-pat");
        assertThat(verb.get()).isEqualTo("GET");
    }

    @Test
    void revokedAdministratorCannotReadEvenWithAnOtherwiseValidIdentity() throws Exception {
        var reader = reader(false, 200);
        assertThatThrownBy(() -> reader.get(binding, "pat", "a").block()).hasMessage("HALO_ADMIN_REQUIRED");
        assertThat(commentReads).hasValue(0);
    }

    @Test
    void forbiddenIsAnErrorAndNeverFallsBackToInternalResources() throws Exception {
        var reader = reader(true, 403);
        assertThatThrownBy(() -> reader.get(binding, "pat", "a").block()).hasMessage("HALO_READ_FORBIDDEN");
    }

    @Test
    void missingCommentReturnsNoBody() throws Exception {
        assertThat(reader(true, 404).get(binding, "pat", "a").block()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"200, 瞬间 · 照片 & 近况", "403, 瞬间", "404, 瞬间"})
    void momentCaptionUsesThePatAndFallsBackWhenUnavailable(int status, String caption) throws Exception {
        momentStatus = status;
        var comment = new Comment();
        comment.setSpec(new Comment.CommentSpec());
        var ref = new Ref();
        ref.setGroup("moment.halo.run");
        ref.setKind("Moment");
        ref.setName("moment-example");
        comment.getSpec().setSubjectRef(ref);

        assertThat(reader(true, 200).title(comment, "read-pat").block()).isEqualTo(caption);
        assertThat(momentRequest).hasValue("/apis/api.moment.halo.run/v1alpha1/moments/moment-example");
        assertThat(authorization).hasValue("Bearer read-pat");
    }
}
