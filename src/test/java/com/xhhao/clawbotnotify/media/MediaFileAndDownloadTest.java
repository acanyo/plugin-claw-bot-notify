package com.xhhao.clawbotnotify.media;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class MediaFileAndDownloadTest {
    @TempDir
    Path root;

    final ObjectMapper json = new ObjectMapper();
    static final String ID = "image-" + "a".repeat(64);

    static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void storedImagesAreEncryptedIntegrityCheckedAndExpire() throws Exception {
        var store = new MediaFileStore(new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])), root);
        byte[] bytes = png();
        var image = store.save(ID, bytes).block();
        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(Files.readAllBytes(root.resolve(ID + ".enc"))).isNotEqualTo(bytes);
        assertThat(store.read(image).block()).isEqualTo(bytes);
        var altered = new MediaFileStore.Image(
                ID, image.filename(), image.mediaType(), image.size(), "bad-hash", image.expiresAt());
        assertThatThrownBy(() -> store.read(altered).block()).hasMessage("MEDIA_CONTENT_CHANGED");
        var expired = new MediaFileStore.Image(
                ID,
                image.filename(),
                image.mediaType(),
                image.size(),
                image.sha256(),
                Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> store.read(expired).block()).hasMessage("MEDIA_EXPIRED");
        Files.setLastModifiedTime(
                root.resolve(ID + ".enc"),
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(86401)));
        store.cleanup().block();
        assertThat(Files.exists(root.resolve(ID + ".enc"))).isFalse();
    }

    @Test
    void rejectsNonImagesOversizedFilesAndUntrustedPaths() throws Exception {
        var store = new MediaFileStore(new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])), root);
        assertThatThrownBy(() -> store.save(ID, "not-image".getBytes()).block()).hasMessage("MEDIA_IMAGE_INVALID");
        assertThatThrownBy(() -> store.save(ID, new byte[WechatMediaDownloader.MAX_BYTES + 1])
                        .block())
                .hasMessage("MEDIA_TOO_LARGE");
        assertThatThrownBy(() -> store.save("../outside", png()).block()).hasMessage("MEDIA_ID_INVALID");
    }

    @Test
    void acceptsOfficialAesKeyEncodingsAndDecryptsExactPayload() throws Exception {
        byte[] key = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] payload = png(), encrypted = cipher.doFinal(payload);
        assertThat(WechatMediaDownloader.decrypt(encrypted, key)).isEqualTo(payload);
        for (String encoded : new String[] {
            Base64.getEncoder().encodeToString(key),
            Base64.getEncoder().encodeToString(HexFormat.of().formatHex(key).getBytes())
        }) {
            var image = json.createObjectNode();
            image.putObject("media").put("aes_key", encoded);
            assertThat(WechatMediaDownloader.key(image)).isEqualTo(key);
        }
        var image = json.createObjectNode().put("aeskey", HexFormat.of().formatHex(key));
        assertThat(WechatMediaDownloader.key(image)).isEqualTo(key);
        image.put("aeskey", "bad-key");
        assertThatThrownBy(() -> WechatMediaDownloader.key(image)).hasMessage("MEDIA_KEY_INVALID");
        assertThatThrownBy(() -> WechatMediaDownloader.decrypt(new byte[] {1}, key))
                .hasMessage("MEDIA_DECRYPT_FAILED");
    }

    @Test
    void fixedCdnDestinationRejectsRedirectTargetsAndNeverSendsCredentials() throws Exception {
        for (String url : new String[] {
            "http://novac2c.cdn.weixin.qq.com/c2c/download",
            "https://evil.test/c2c/download",
            "https://novac2c.cdn.weixin.qq.com@evil.test/c2c/download",
            "https://novac2c.cdn.weixin.qq.com:8443/c2c/download",
            "https://novac2c.cdn.weixin.qq.com/other"
        }) {
            var media = json.createObjectNode().put("full_url", url);
            assertThatThrownBy(() -> WechatMediaDownloader.destination(media)).hasMessage("MEDIA_UNSAFE_DESTINATION");
        }
        var item = json.createObjectNode();
        item.putObject("image_item").putObject("media").put("encrypt_query_param", "a+/=b");
        var downloader = new WechatMediaDownloader(WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.headers().getFirst("Authorization")).isNull();
                    assertThat(request.headers().getFirst("AuthorizationType")).isNull();
                    assertThat(request.url().toString()).contains("encrypted_query_param=a%2B/%3Db");
                    return Mono.just(ClientResponse.create(HttpStatus.FOUND)
                            .header("Location", "http://localhost/private")
                            .build());
                })
                .build());
        assertThatThrownBy(() -> downloader.download(item).block()).hasMessage("MEDIA_DOWNLOAD_FAILED");
    }
}
