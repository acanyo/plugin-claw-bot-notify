package com.xhhao.clawbotnotify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/** Wire contract: Tencent/openclaw-weixin @ 0ad1ae91347a938f52f1e4e2abd6dcda51e05f9e. */
@Component
public class WechatProtocolClient {
    public static final String PROTOCOL_VERSION = "3.0.0-beta-0";
    private static final Map<String, String> BASE_INFO =
            Map.of("channel_version", PROTOCOL_VERSION, "bot_agent", "HaloClawBot/1.0.0");
    private final WebClient http;
    private final WechatDestinationPolicy destinations;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public WechatProtocolClient(WechatDestinationPolicy destinations) {
        this(
                WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                                .followRedirect(false)
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)))
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                        .build(),
                destinations);
    }

    WechatProtocolClient(WebClient http, WechatDestinationPolicy destinations) {
        this.http = http;
        this.destinations = destinations;
    }

    public Mono<JsonNode> login() {
        return request(
                        HttpMethod.POST,
                        WechatDestinationPolicy.DEFAULT_BASE,
                        "/ilink/bot/get_bot_qrcode?bot_type=3",
                        null,
                        Map.of("local_token_list", List.of()),
                        Duration.ofSeconds(15))
                .map(body -> {
                    requiredText(body, "qrcode");
                    requiredText(body, "qrcode_img_content");
                    return body;
                });
    }

    public Mono<JsonNode> loginStatus(String base, String qr, String pin) {
        var uri = UriComponentsBuilder.fromPath("/ilink/bot/get_qrcode_status").queryParam("qrcode", "{qr}");
        var variables = new java.util.HashMap<String, String>();
        variables.put("qr", qr);
        if (pin != null) {
            uri.queryParam("verify_code", "{pin}");
            variables.put("pin", pin);
        }
        return request(
                HttpMethod.GET,
                base,
                uri.encode().buildAndExpand(variables).toUriString(),
                null,
                null,
                Duration.ofSeconds(35));
    }

    public Mono<JsonNode> poll(String base, String token, String cursor) {
        return request(
                        HttpMethod.POST,
                        base,
                        "/ilink/bot/getupdates",
                        token,
                        Map.of("get_updates_buf", cursor == null ? "" : cursor, "base_info", BASE_INFO),
                        Duration.ofSeconds(35))
                .map(body -> {
                    checkResultCodes(body);
                    if (!body.has("msgs")) ((com.fasterxml.jackson.databind.node.ObjectNode) body).putArray("msgs");
                    if (!body.path("msgs").isArray()
                            || !body.path("get_updates_buf").isTextual()) invalid();
                    return body;
                });
    }

    public Mono<String> send(String base, String token, String to, String context, String clientId, String text) {
        return sendQuoted(base, token, to, context, clientId, text, null);
    }

    public Mono<String> sendQuoted(
            String base,
            String token,
            String to,
            String context,
            String clientId,
            String text,
            String quotedMessageId) {
        if (context == null || context.isBlank())
            return Mono.error(new ChannelException(ChannelException.Code.CONTEXT_UNAVAILABLE));
        var item = new java.util.HashMap<String, Object>();
        item.put("type", 1);
        item.put("text_item", Map.of("text", text));
        if (quotedMessageId != null && !quotedMessageId.isBlank())
            item.put("ref_msg", Map.of("svr_id", quotedMessageId));
        var message = Map.of(
                "from_user_id",
                "",
                "to_user_id",
                to,
                "client_id",
                clientId,
                "message_type",
                2,
                "message_state",
                2,
                "context_token",
                context,
                "item_list",
                List.of(item));
        return request(
                        HttpMethod.POST,
                        base,
                        "/ilink/bot/sendmessage",
                        token,
                        Map.of("msg", message, "base_info", BASE_INFO),
                        Duration.ofSeconds(15))
                .map(body -> {
                    checkSendResponse(body);
                    return body.path("message_id").asText("");
                });
    }

    private Mono<JsonNode> request(
            HttpMethod method, String base, String path, String token, Object body, Duration timeout) {
        return Mono.defer(() -> {
                    var target = destinations.validate(base).resolve(path);
                    var request = http.method(method)
                            .uri(target)
                            .header("iLink-App-Id", "bot")
                            .header("iLink-App-ClientVersion", "196608");
                    if (method == HttpMethod.POST) {
                        request.contentType(MediaType.APPLICATION_JSON)
                                .header("AuthorizationType", "ilink_bot_token")
                                .header(
                                        "X-WECHAT-UIN",
                                        Base64.getEncoder()
                                                .encodeToString(Long.toString(Integer.toUnsignedLong(random.nextInt()))
                                                        .getBytes(StandardCharsets.UTF_8)));
                    }
                    if (token != null) request.headers(h -> h.setBearerAuth(token));
                    return (body == null ? request : request.bodyValue(body))
                            .exchangeToMono(response -> {
                                if (!response.statusCode().is2xxSuccessful()) {
                                    return response.releaseBody()
                                            .then(Mono.error(
                                                    new ChannelException(ChannelException.Code.HTTP_REJECTED)));
                                }
                                return response.bodyToMono(String.class)
                                        .switchIfEmpty(Mono.error(
                                                new ChannelException(ChannelException.Code.INVALID_RESPONSE)))
                                        .map(raw -> {
                                            try {
                                                var tree = json.readTree(raw);
                                                if (tree == null || !tree.isObject()) invalid();
                                                return tree;
                                            } catch (Exception e) {
                                                throw new ChannelException(ChannelException.Code.INVALID_RESPONSE);
                                            }
                                        });
                            })
                            .timeout(timeout);
                })
                .onErrorMap(
                        e -> !(e instanceof ChannelException),
                        e -> new ChannelException(
                                e instanceof TimeoutException
                                        ? ChannelException.Code.TIMEOUT
                                        : ChannelException.Code.NETWORK));
    }

    public static String requiredText(JsonNode body, String field) {
        if (!body.path(field).isTextual() || body.path(field).asText().isBlank()) invalid();
        return body.path(field).asText();
    }

    private static void checkResultCodes(JsonNode body) {
        // The upstream client treats omitted default result codes as zero. Each
        // endpoint additionally validates its own response structure.
        if (body.has("ret") && !body.path("ret").isIntegralNumber()) invalid();
        if (body.has("errcode") && !body.path("errcode").isIntegralNumber()) invalid();
        if ((body.has("ret") && body.path("ret").bigIntegerValue().signum() != 0)
                || (body.has("errcode")
                        && body.path("errcode").bigIntegerValue().signum() != 0)) {
            throw new ChannelException(ChannelException.Code.BUSINESS_REJECTED);
        }
    }

    private static void checkSendResponse(JsonNode body) {
        checkResultCodes(body);
        if (body.has("message_id")) {
            var id = body.path("message_id");
            if ((!id.isIntegralNumber() && !id.isTextual())
                    || id.asText().isBlank()
                    || (id.isIntegralNumber() && id.bigIntegerValue().signum() < 0)) invalid();
        }
        if (body.has("errmsg") && !body.path("errmsg").isTextual()) invalid();
        if (!body.has("ret")) {
            // Official SendMessageResp permits default-zero fields to be omitted,
            // including an empty object. Accept only this known response schema;
            // unexpected fields or an unconfirmed error message remain ambiguous.
            var known = Set.of("message_id", "errmsg", "errcode");
            var fields = body.fieldNames();
            while (fields.hasNext()) if (!known.contains(fields.next())) invalid();
            if (!body.path("errmsg").asText("").isEmpty()) invalid();
        }
    }

    private static void invalid() {
        throw new ChannelException(ChannelException.Code.INVALID_RESPONSE);
    }
}
