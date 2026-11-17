package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.infra.ExternalUrlSupplier;

class ThreadedTargetHttpTest {
    DisposableServer server;
    final List<String> credentials = new CopyOnWriteArrayList<>();
    final WechatBinding binding = new WechatBinding();

    ThreadedTargetHttpTest() {
        var spec = new WechatBinding.Spec();
        binding.setSpec(spec);
        spec.setEnabled(true);
        spec.setHaloUsername("admin");
        spec.setNotificationStartAt(Instant.parse("2026-11-13T00:00:00Z"));
    }

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    CommentTargetReader reader(String replyParent, String created, int replyStatus) throws Exception {
        var json = new ObjectMapper();
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    credentials.add(request.requestHeaders().get("Authorization"));
                    response.header("Content-Type", "application/json");
                    if (request.uri().endsWith("users/-"))
                        return response.sendString(Mono.just("{\"user\":{\"metadata\":{\"name\":\"admin\"}}}"));
                    if (request.uri().contains("/replies/")) {
                        var body = json.createObjectNode();
                        body.put("apiVersion", "content.halo.run/v1alpha1");
                        body.put("kind", "Reply");
                        body.putObject("metadata").put("name", "child").put("creationTimestamp", created);
                        body.putObject("spec")
                                .put("commentName", replyParent)
                                .put("raw", "visitor follow-up")
                                .put("content", "<p>visitor follow-up</p>");
                        return response.status(replyStatus).sendString(Mono.just(body.toString()));
                    }
                    return response.sendString(
                            Mono.just(
                                    "{\"apiVersion\":\"content.halo.run/v1alpha1\",\"kind\":\"Comment\",\"metadata\":{\"name\":\"old-root\",\"creationTimestamp\":\"2026-01-01T00:00:00Z\"},\"spec\":{\"raw\":\"old root\",\"subjectRef\":{\"group\":\"content.halo.run\",\"kind\":\"Post\",\"name\":\"post\"}}}"));
                })
                .bindNow();
        var external = mock(ExternalUrlSupplier.class);
        when(external.getRaw())
                .thenReturn(URI.create("http://127.0.0.1:" + server.port()).toURL());
        var roles = mock(RoleService.class);
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("super-role"));
        return new CommentTargetReader(new HaloCommentReader(new HaloIdentityVerifier(external), roles));
    }

    @Test
    void recentReplyUnderOldRootIsAuthorizedWithPatAndReadable() throws Exception {
        var target = reader("old-root", "2026-11-13T01:00:00Z", 200)
                .get(binding, "owner-pat", "old-root", "child")
                .block();
        assertThat(target.reply().getSpec().getRaw()).isEqualTo("visitor follow-up");
        assertThat(target.comment().getSpec().getRaw()).isEqualTo("old root");
        assertThat(credentials).isNotEmpty().allMatch("Bearer owner-pat"::equals);
    }

    @Test
    void replyFromAnotherParentCannotSelectThatParent() throws Exception {
        assertThat(reader("other-root", "2026-11-13T01:00:00Z", 200)
                        .get(binding, "owner-pat", "old-root", "child")
                        .block())
                .isNull();
    }

    @Test
    void oldReplyCannotBeSelectedWithNewBinding() throws Exception {
        assertThat(reader("old-root", "2026-11-12T01:00:00Z", 200)
                        .get(binding, "owner-pat", "old-root", "child")
                        .block())
                .isNull();
    }

    @Test
    void forbiddenReplyNeverFallsBackToParent() throws Exception {
        var reader = reader("old-root", "2026-11-13T01:00:00Z", 403);
        assertThatThrownBy(() ->
                        reader.get(binding, "owner-pat", "old-root", "child").block())
                .hasMessage("HALO_READ_FORBIDDEN");
    }
}
