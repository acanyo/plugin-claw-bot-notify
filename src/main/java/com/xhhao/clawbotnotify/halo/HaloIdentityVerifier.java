package com.xhhao.clawbotnotify.halo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import run.halo.app.infra.ExternalUrlSupplier;

/** Uses the installation's own address; never accepts a caller-supplied Halo URL. */
@Component
public class HaloIdentityVerifier {
    private final ExternalUrlSupplier externalUrl;
    private final WebClient http = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                    .followRedirect(false)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public HaloIdentityVerifier(ExternalUrlSupplier externalUrl) {
        this.externalUrl = externalUrl;
    }

    public Mono<Void> verify(String token, String expectedUsername) {
        return get(token, "/apis/api.console.halo.run/v1alpha1/users/-", false)
                .onErrorMap(
                        e -> !"HALO_ADDRESS_INVALID".equals(e.getMessage()),
                        e -> new IllegalStateException("HALO_AUTH_FAILED"))
                .flatMap(body -> {
                    String name =
                            body.path("user").path("metadata").path("name").asText();
                    return expectedUsername.equals(name)
                                    && !body.path("user")
                                            .path("spec")
                                            .path("disabled")
                                            .asBoolean()
                            ? Mono.empty()
                            : Mono.error(new IllegalStateException("HALO_IDENTITY_MISMATCH"));
                });
    }

    /** Only fixed installation-local API paths may be supplied by plugin code. */
    public Mono<com.fasterxml.jackson.databind.JsonNode> postReply(
            String token, String commentName, String raw, String html) {
        return postReply(token, commentName, null, raw, html);
    }

    public Mono<com.fasterxml.jackson.databind.JsonNode> postReply(
            String token, String commentName, String quoteReply, String raw, String html) {
        var request = new java.util.HashMap<String, Object>();
        request.put("raw", raw);
        request.put("content", html);
        request.put("allowNotification", true);
        request.put("hidden", false);
        if (quoteReply != null) request.put("quoteReply", quoteReply);
        return Mono.defer(() -> {
                    URI base;
                    try {
                        base = externalUrl.getRaw().toURI();
                    } catch (Exception e) {
                        return Mono.error(new HaloWriteException("HALO_ADDRESS_INVALID", true));
                    }
                    if (base.getHost() == null
                            || base.getUserInfo() != null
                            || base.getQuery() != null
                            || base.getFragment() != null
                            || !("https".equals(base.getScheme()) || "http".equals(base.getScheme())))
                        return Mono.error(new HaloWriteException("HALO_ADDRESS_INVALID", true));
                    String path = "/apis/api.console.halo.run/v1alpha1/comments/"
                            + org.springframework.web.util.UriUtils.encodePathSegment(
                                    commentName, java.nio.charset.StandardCharsets.UTF_8)
                            + "/reply";
                    return http.post()
                            .uri(base.toString().replaceAll("/$", "") + path)
                            .headers(h -> h.setBearerAuth(token))
                            .bodyValue(request)
                            .exchangeToMono(response -> {
                                int code = response.statusCode().value();
                                if (!response.statusCode().is2xxSuccessful())
                                    return response.releaseBody()
                                            .then(Mono.error(new HaloWriteException(
                                                    switch (code) {
                                                        case 401, 403 -> "HALO_WRITE_FORBIDDEN";
                                                        case 400, 422 -> "HALO_WRITE_CONTENT_REJECTED";
                                                        case 404 -> "COMMENT_DELETED";
                                                        default -> "HALO_WRITE_FAILED";
                                                    },
                                                    java.util.Set.of(400, 401, 403, 404, 422)
                                                            .contains(code))));
                                return response.bodyToMono(String.class)
                                        .switchIfEmpty(Mono.error(new HaloWriteException("HALO_WRITE_UNKNOWN", false)))
                                        .flatMap(body -> {
                                            try {
                                                return Mono.just(json.readTree(body));
                                            } catch (Exception e) {
                                                return Mono.error(new HaloWriteException("HALO_WRITE_UNKNOWN", false));
                                            }
                                        });
                            })
                            .timeout(Duration.ofSeconds(15));
                })
                .onErrorMap(
                        e -> !(e instanceof HaloWriteException),
                        e -> new HaloWriteException("HALO_WRITE_UNKNOWN", false));
    }

    /** Only fixed installation-local API paths may be supplied by plugin code. */
    public Mono<com.fasterxml.jackson.databind.JsonNode> get(String token, String path, boolean allowMissing) {
        return Mono.defer(() -> {
                    var configured = externalUrl.getRaw();
                    URI base;
                    try {
                        base = configured == null ? null : configured.toURI();
                    } catch (java.net.URISyntaxException e) {
                        return Mono.error(new IllegalStateException("HALO_ADDRESS_INVALID"));
                    }
                    if (base == null
                            || base.getHost() == null
                            || base.getUserInfo() != null
                            || base.getQuery() != null
                            || base.getFragment() != null
                            || !("https".equals(base.getScheme()) || "http".equals(base.getScheme())))
                        return Mono.error(new IllegalStateException("HALO_ADDRESS_INVALID"));
                    if (token == null || token.isBlank())
                        return Mono.error(new IllegalStateException("HALO_AUTH_FAILED"));
                    return http.get()
                            .uri(base.toString().replaceAll("/$", "") + path)
                            .headers(h -> h.setBearerAuth(token))
                            .exchangeToMono(response -> {
                                if (allowMissing && response.statusCode().value() == 404)
                                    return response.releaseBody().then(Mono.empty());
                                if (!response.statusCode().is2xxSuccessful())
                                    return response.releaseBody()
                                            .then(Mono.error(new IllegalStateException("HALO_READ_FORBIDDEN")));
                                return response.bodyToMono(String.class)
                                        .switchIfEmpty(Mono.error(new IllegalStateException("HALO_AUTH_FAILED")))
                                        .flatMap(raw -> {
                                            try {
                                                return Mono.just(json.readTree(raw));
                                            } catch (Exception e) {
                                                return Mono.error(new IllegalStateException("HALO_AUTH_FAILED"));
                                            }
                                        });
                            })
                            .timeout(Duration.ofSeconds(10));
                })
                .onErrorMap(
                        e -> !(e instanceof IllegalStateException), e -> new IllegalStateException("HALO_AUTH_FAILED"));
    }
}
