package com.xhhao.clawbotnotify.media;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/** Pinned Tencent media contract: AES-128-ECB/PKCS#7, explicit CDN host, no bot/PAT credentials. */
@Component
public class WechatMediaDownloader {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private final WebClient http;

    public WechatMediaDownloader() {
        this(WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().followRedirect(false).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES + 32))
                .build());
    }

    WechatMediaDownloader(WebClient http) {
        this.http = http;
    }

    public Mono<byte[]> download(JsonNode item) {
        return Mono.defer(() -> {
                    JsonNode image = item.path("image_item");
                    JsonNode media = image.path("media");
                    URI target = destination(media);
                    byte[] key = key(image);
                    return http.get()
                            .uri(target)
                            .exchangeToMono(response -> {
                                if (!response.statusCode().is2xxSuccessful())
                                    return response.releaseBody()
                                            .then(Mono.error(new IllegalStateException("MEDIA_DOWNLOAD_FAILED")));
                                long size = response.headers().contentLength().orElse(0);
                                if (size > MAX_BYTES + 32)
                                    return response.releaseBody()
                                            .then(Mono.error(new IllegalStateException("MEDIA_TOO_LARGE")));
                                return response.bodyToMono(byte[].class);
                            })
                            .timeout(Duration.ofSeconds(25))
                            .publishOn(Schedulers.boundedElastic())
                            .map(bytes -> decrypt(bytes, key));
                })
                .onErrorMap(error -> new IllegalStateException(code(error)));
    }

    static URI destination(JsonNode media) {
        try {
            String full = media.path("full_url").asText("");
            String query = media.path("encrypt_query_param").asText("");
            if (full.isBlank() && (query.isBlank() || query.length() > 8192)) throw new IllegalArgumentException();
            var uri = URI.create(
                    full.isBlank()
                            ? "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param="
                                    + UriUtils.encodeQueryParam(query, StandardCharsets.UTF_8)
                            : full);
            if (!"https".equals(uri.getScheme())
                    || !"novac2c.cdn.weixin.qq.com".equals(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !"/c2c/download".equals(uri.getPath())) throw new IllegalArgumentException();
            return uri;
        } catch (Exception error) {
            throw new IllegalStateException("MEDIA_UNSAFE_DESTINATION");
        }
    }

    static byte[] key(JsonNode image) {
        try {
            String hex = image.path("aeskey").asText("");
            if (!hex.isBlank()) {
                if (!hex.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException();
                return HexFormat.of().parseHex(hex);
            }
            String encoded = image.path("media").path("aes_key").asText("");
            if (encoded.isBlank()) return null;
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length == 32 && new String(key, StandardCharsets.US_ASCII).matches("[a-fA-F0-9]{32}"))
                key = HexFormat.of().parseHex(new String(key, StandardCharsets.US_ASCII));
            if (key.length != 16) throw new IllegalArgumentException();
            return key;
        } catch (Exception error) {
            throw new IllegalStateException("MEDIA_KEY_INVALID");
        }
    }

    static byte[] decrypt(byte[] bytes, byte[] key) {
        try {
            byte[] result = bytes;
            if (key != null) {
                var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
                result = cipher.doFinal(bytes);
            }
            if (result.length == 0 || result.length > MAX_BYTES) throw new IllegalStateException("MEDIA_TOO_LARGE");
            return result;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("MEDIA_DECRYPT_FAILED");
        }
    }

    private static String code(Throwable error) {
        return error.getMessage() != null && error.getMessage().matches("MEDIA_[A-Z_]+")
                ? error.getMessage()
                : "MEDIA_DOWNLOAD_FAILED";
    }
}
