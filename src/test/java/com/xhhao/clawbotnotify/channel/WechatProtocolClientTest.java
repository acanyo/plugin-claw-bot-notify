package com.xhhao.clawbotnotify.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class WechatProtocolClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private DisposableServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestUri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> appVersion = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
    }

    private WechatProtocolClient client(String response, int status) {
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, reply) -> {
                    calls.incrementAndGet();
                    requestUri.set(request.uri());
                    authorization.set(request.requestHeaders().get("Authorization"));
                    appVersion.set(request.requestHeaders().get("iLink-App-ClientVersion"));
                    return request.receive()
                            .aggregate()
                            .asString()
                            .defaultIfEmpty("")
                            .flatMap(text -> {
                                requestBody.set(text);
                                reply.status(status).header("Content-Type", "application/json");
                                if (status == 302)
                                    reply.header("Location", "http://127.0.0.1:" + server.port() + "/redirected");
                                return reply.sendString(Mono.just(response)).then();
                            });
                })
                .bindNow();
        var testDestinations = new WechatDestinationPolicy() {
            @Override
            public URI validate(String ignored) {
                return URI.create("http://127.0.0.1:" + server.port());
            }
        };
        return new WechatProtocolClient(WebClient.builder().build(), testDestinations);
    }

    @Test
    void loginUsesPinnedBotTypeAndOmitsBearerCredential() throws Exception {
        client("{\"qrcode\":\"opaque-qr\",\"qrcode_img_content\":\"https://example.test/qr\"}", 200)
                .login()
                .block();
        assertThat(requestUri.get()).isEqualTo("/ilink/bot/get_bot_qrcode?bot_type=3");
        assertThat(authorization.get()).isNull();
        assertThat(appVersion.get()).isEqualTo("196608");
        assertThat(json.readTree(requestBody.get()).path("local_token_list").isArray())
                .isTrue();
    }

    @Test
    void pinIsSubmittedOnlyToQrStatus() {
        client("{\"status\":\"scaned\"}", 200)
                .loginStatus(WechatDestinationPolicy.DEFAULT_BASE, "q/r&value+ space", "123456")
                .block();
        assertThat(requestUri.get()).contains("qrcode=q%2Fr%26value%2B%20space", "verify_code=123456");
        assertThat(authorization.get()).isNull();
        assertThat(requestBody.get()).isEmpty();
    }

    @Test
    void sendPreservesClientIdAndUint64WithoutRounding() throws Exception {
        String id = "18446744073709551615";
        var client = client("{\"ret\":0,\"message_id\":" + id + "}", 200);
        assertThat(client.send(
                                WechatDestinationPolicy.DEFAULT_BASE,
                                "secret-bot-token",
                                "owner",
                                "session-context",
                                "stable-id",
                                "line 1\nline 2")
                        .block())
                .isEqualTo(id);
        JsonNode msg = json.readTree(requestBody.get()).path("msg");
        assertThat(authorization.get()).isEqualTo("Bearer secret-bot-token");
        assertThat(msg.path("client_id").asText()).isEqualTo("stable-id");
        assertThat(msg.path("context_token").asText()).isEqualTo("session-context");
        assertThat(msg.path("message_type").asInt()).isEqualTo(2);
        assertThat(msg.path("item_list").get(0).path("text_item").path("text").asText())
                .isEqualTo("line 1\nline 2");
    }

    @Test
    void nativeQuotePreservesLargeServerIdentifierAsAString() throws Exception {
        client("{}", 200)
                .sendQuoted(
                        WechatDestinationPolicy.DEFAULT_BASE,
                        "token",
                        "owner",
                        "context",
                        "stable",
                        "回复已发布",
                        "18446744073709551615")
                .block();
        var ref = json.readTree(requestBody.get())
                .path("msg")
                .path("item_list")
                .get(0)
                .path("ref_msg")
                .path("svr_id");
        assertThat(ref.isTextual()).isTrue();
        assertThat(ref.asText()).isEqualTo("18446744073709551615");
    }

    @Test
    void pollPreservesCursorAndLargeInboundMessageId() throws Exception {
        var client = client(
                "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[{\"message_id\":18446744073709551615}]}", 200);
        var batch = client.poll(WechatDestinationPolicy.DEFAULT_BASE, "token", "previous")
                .block();
        assertThat(batch.path("msgs").get(0).path("message_id").asText()).isEqualTo("18446744073709551615");
        assertThat(json.readTree(requestBody.get()).path("get_updates_buf").asText())
                .isEqualTo("previous");
        assertThat(json.readTree(requestBody.get())
                        .path("base_info")
                        .path("channel_version")
                        .asText())
                .isEqualTo("3.0.0-beta-0");
    }

    @Test
    void emptyPollCanOmitMessages() {
        assertThat(client("{\"ret\":0,\"get_updates_buf\":\"next\"}", 200)
                        .poll(WechatDestinationPolicy.DEFAULT_BASE, "token", "previous")
                        .block()
                        .path("msgs")
                        .isEmpty())
                .isTrue();
    }

    @Test
    void pollAcceptsRealResponseWithOmittedDefaultSuccessCode() {
        var response = client("{\"msgs\":[],\"sync_buf\":\"legacy\",\"get_updates_buf\":\"next\"}", 200)
                .poll(WechatDestinationPolicy.DEFAULT_BASE, "token", "previous")
                .block();
        assertThat(response.path("get_updates_buf").asText()).isEqualTo("next");
        assertThat(response.path("msgs").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"msgs\":[]}",
                "{\"ret\":\"0\",\"get_updates_buf\":\"next\"}",
                "{\"errcode\":\"0\",\"get_updates_buf\":\"next\"}"
            })
    void omittedPollSuccessCodeDoesNotPermitMalformedResponses(String response) {
        var client = client(response, 200);
        assertThatThrownBy(() -> client.poll(WechatDestinationPolicy.DEFAULT_BASE, "token", "previous")
                        .block())
                .hasMessage("INVALID_RESPONSE");
    }

    @Test
    void pollRejectsNonzeroErrorWithoutRet() {
        var client = client("{\"errcode\":-14}", 200);
        assertThatThrownBy(() -> client.poll(WechatDestinationPolicy.DEFAULT_BASE, "token", "previous")
                        .block())
                .hasMessage("BUSINESS_REJECTED");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"errmsg\":\"\"}",
                "{\"message_id\":\"1\"}",
                "{\"message_id\":18446744073709551615}",
                "{\"errcode\":0}"
            })
    void sendAcceptsKnownResponseWithOmittedDefaultZeroCode(String response) {
        var client = client(response, 200);
        assertThat(client.send(WechatDestinationPolicy.DEFAULT_BASE, "token", "owner", "context", "stable", "test")
                        .block())
                .isNotNull();
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "null",
                "not-json",
                "{\"unexpected\":true}",
                "{\"ret\":\"0\"}",
                "{\"ret\":0,\"errcode\":\"broken\"}",
                "{\"errmsg\":\"unconfirmed\"}",
                "{\"message_id\":false}",
                "{\"message_id\":-1}"
            })
    void malformedSuccessIsRejected(String response) {
        var client = client(response, 200);
        assertThatThrownBy(() -> client.send(
                                WechatDestinationPolicy.DEFAULT_BASE, "token", "owner", "context", "stable", "test")
                        .block())
                .isInstanceOf(ChannelException.class)
                .hasMessage("INVALID_RESPONSE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ret\":-14}", "{\"ret\":4294967296}", "{\"ret\":0,\"errcode\":-1}", "{\"errcode\":-1}"})
    void businessFailuresAreNeverAccepted(String response) {
        var client = client(response, 200);
        assertThatThrownBy(() -> client.send(
                                WechatDestinationPolicy.DEFAULT_BASE, "token", "owner", "context", "stable", "test")
                        .block())
                .isInstanceOf(ChannelException.class)
                .hasMessage("BUSINESS_REJECTED");
    }

    @Test
    void redirectsAreNotFollowedAndErrorBodiesAreDiscarded() {
        var client = client("{\"error\":\"secret-bot-token\"}", 302);
        assertThatThrownBy(() -> client.send(
                                WechatDestinationPolicy.DEFAULT_BASE,
                                "secret-bot-token",
                                "owner",
                                "context",
                                "stable",
                                "test")
                        .block())
                .hasMessage("HTTP_REJECTED")
                .hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void missingContextNeverSendsAnHttpRequest() {
        var client = client("{\"ret\":0}", 200);
        assertThatThrownBy(() -> client.send(
                                WechatDestinationPolicy.DEFAULT_BASE, "token", "owner", null, "stable", "test")
                        .block())
                .hasMessage("CONTEXT_UNAVAILABLE");
        assertThat(calls.get()).isZero();
    }
}
