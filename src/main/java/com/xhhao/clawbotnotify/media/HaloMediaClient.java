package com.xhhao.clawbotnotify.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import run.halo.app.infra.ExternalUrlSupplier;

/** Installation-local APIs enforce the bound PAT's attachment, moment and post permissions. No redirects/retries. */
@Component
public class HaloMediaClient {
    private final ExternalUrlSupplier externalUrl;
    private final WebClient http;
    private final ObjectMapper json = new ObjectMapper();

    public record Attachment(String name, String url, String mediaType) {}

    @org.springframework.beans.factory.annotation.Autowired
    public HaloMediaClient(ExternalUrlSupplier externalUrl) {
        this(
                externalUrl,
                WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                                .followRedirect(false)
                                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)))
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024))
                        .build());
    }

    HaloMediaClient(ExternalUrlSupplier externalUrl, WebClient http) {
        this.externalUrl = externalUrl;
        this.http = http;
    }

    public Mono<Attachment> upload(String token, MediaFileStore.Image image, byte[] bytes) {
        var body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return image.filename();
                    }
                })
                .filename(image.filename())
                .contentType(MediaType.parseMediaType(image.mediaType()));
        return request(http.post()
                        .uri(target("/apis/console.api.storage.halo.run/v1alpha1/attachments/-/upload"))
                        .headers(h -> h.setBearerAuth(token))
                        .body(BodyInserters.fromMultipartData(body.build())))
                .map(value -> {
                    String name = value.path("metadata").path("name").asText();
                    String link = value.path("status").path("permalink").asText();
                    if (link.isBlank()) link = value.path("spec").path("url").asText();
                    if (name.isBlank() || link.isBlank()) throw new IllegalStateException("MEDIA_RESULT_UNKNOWN");
                    URI uri = target("/").resolve(link);
                    if (!List.of("http", "https").contains(uri.getScheme())
                            || uri.getHost() == null
                            || uri.getUserInfo() != null) throw new IllegalStateException("MEDIA_RESULT_UNKNOWN");
                    return new Attachment(name, uri.toString(), image.mediaType());
                });
    }

    public Mono<JsonNode> post(String token, String name) {
        return request(http.get().uri(target(postPath(name))).headers(h -> h.setBearerAuth(token)));
    }

    public Mono<JsonNode> setCover(String token, JsonNode snapshot, String cover) {
        var resource = snapshot.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) resource.path("spec")).put("cover", cover);
        return request(http.put()
                .uri(target(postPath(resource.path("metadata").path("name").asText())))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(resource.toString()));
    }

    public Mono<JsonNode> moment(
            String token, String name, String owner, String raw, String visible, List<Attachment> images) {
        String html = com.xhhao.clawbotnotify.format.MessageFormatting.replyHtml(raw);
        var payload = Map.of(
                "apiVersion",
                "moment.halo.run/v1alpha1",
                "kind",
                "Moment",
                "metadata",
                Map.of("name", name),
                "spec",
                Map.of(
                        "owner",
                        owner,
                        "visible",
                        visible,
                        "approved",
                        true,
                        "releaseTime",
                        java.time.Instant.now().toString(),
                        "content",
                        Map.of(
                                "raw",
                                html,
                                "html",
                                html,
                                "medium",
                                images.stream()
                                        .map(image ->
                                                Map.of("type", "PHOTO", "url", image.url(), "originType", "attachment"))
                                        .toList())));
        return momentResource(token, payload);
    }

    /** Generic resource creation preserves the author; the Moments UC/Console services overwrite it. */
    public Mono<JsonNode> momentResource(String token, Object payload) {
        return request(http.post()
                .uri(target("/apis/moment.halo.run/v1alpha1/moments"))
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload));
    }

    private Mono<JsonNode> request(WebClient.RequestHeadersSpec<?> request) {
        return request.exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        String code =
                                switch (response.statusCode().value()) {
                                    case 401, 403 -> "MEDIA_PERMISSION_DENIED";
                                    case 409 -> "MEDIA_TARGET_CHANGED";
                                    case 404 -> "MEDIA_TARGET_UNAVAILABLE";
                                    default -> "MEDIA_REQUEST_FAILED";
                                };
                        return response.releaseBody().then(Mono.error(new IllegalStateException(code)));
                    }
                    return response.bodyToMono(String.class)
                            .map(body -> {
                                try {
                                    return json.readTree(body);
                                } catch (Exception error) {
                                    throw new IllegalStateException("MEDIA_RESULT_UNKNOWN");
                                }
                            })
                            .switchIfEmpty(Mono.error(new IllegalStateException("MEDIA_RESULT_UNKNOWN")));
                })
                .timeout(Duration.ofSeconds(40))
                .onErrorMap(error -> new IllegalStateException(
                        error.getMessage() != null && error.getMessage().matches("MEDIA_[A-Z_]+")
                                ? error.getMessage()
                                : "MEDIA_RESULT_UNKNOWN"));
    }

    private URI target(String path) {
        try {
            URI base = externalUrl.getRaw().toURI();
            if (!List.of("http", "https").contains(base.getScheme())
                    || base.getHost() == null
                    || base.getUserInfo() != null
                    || base.getQuery() != null
                    || base.getFragment() != null) throw new IllegalArgumentException();
            return URI.create(base.toString().replaceAll("/+$", "") + path);
        } catch (Exception error) {
            throw new IllegalStateException("HALO_ADDRESS_INVALID");
        }
    }

    private static String postPath(String name) {
        if (!name.matches("[a-z0-9][a-z0-9.-]{0,252}")) throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        return "/apis/content.halo.run/v1alpha1/posts/" + name;
    }
}
