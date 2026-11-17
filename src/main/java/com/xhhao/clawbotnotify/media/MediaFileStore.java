package com.xhhao.clawbotnotify.media;

import com.xhhao.clawbotnotify.credential.CredentialCipher;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Encrypted image cache; raw images never remain on disk, paths are derived only from opaque identifiers. */
@Component
public class MediaFileStore {
    private final CredentialCipher cipher;
    private final Path root;

    public record Image(String id, String filename, String mediaType, long size, String sha256, Instant expiresAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public MediaFileStore(CredentialCipher cipher) {
        this(
                cipher,
                Path.of(System.getenv()
                        .getOrDefault("CLAWBOT_MEDIA_DIR", System.getProperty("user.home") + "/.halo2/clawbot-media")));
    }

    MediaFileStore(CredentialCipher cipher, Path root) {
        this.cipher = cipher;
        this.root = root;
    }

    public Mono<Image> save(String id, byte[] bytes) {
        return Mono.fromCallable(() -> {
                    if (bytes.length == 0 || bytes.length > WechatMediaDownloader.MAX_BYTES)
                        throw new IllegalStateException("MEDIA_TOO_LARGE");
                    String type = imageType(bytes);
                    Files.createDirectories(root);
                    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
                    cleanupBlocking();
                    try (var files = Files.list(root)) {
                        long total = files.filter(p -> !Files.isSymbolicLink(p))
                                .mapToLong(p -> {
                                    try {
                                        return Files.size(p);
                                    } catch (Exception e) {
                                        return 0;
                                    }
                                })
                                .sum();
                        if (total + bytes.length > 200L * 1024 * 1024)
                            throw new IllegalStateException("MEDIA_CACHE_FULL");
                    }
                    Path target = path(id);
                    var temp = Files.createTempFile(
                            root,
                            "incoming-",
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    try {
                        Files.write(temp, cipher.encrypt("media-" + id, bytes));
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                    String ext = type.substring(type.indexOf('/') + 1).replace("jpeg", "jpg");
                    return new Image(
                            id,
                            id + "." + ext,
                            type,
                            bytes.length,
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(bytes)),
                            Instant.now().plusSeconds(86400));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> safe(error));
    }

    public Mono<byte[]> read(Image image) {
        return Mono.fromCallable(() -> {
                    Path path = path(image.id());
                    if (image.expiresAt().isBefore(Instant.now())
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        throw new IllegalStateException("MEDIA_EXPIRED");
                    byte[] bytes = cipher.decrypt("media-" + image.id(), Files.readAllBytes(path));
                    if (!image.sha256()
                            .equals(HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256").digest(bytes))))
                        throw new IllegalStateException("MEDIA_CONTENT_CHANGED");
                    return bytes;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(MediaFileStore::safe);
    }

    public Mono<Void> cleanup() {
        return Mono.fromRunnable(() -> {
                    try {
                        cleanupBlocking();
                    } catch (Exception error) {
                        throw new IllegalStateException("MEDIA_STORAGE_FAILED");
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void cleanupBlocking() throws Exception {
        if (!Files.isDirectory(root)) return;
        try (var paths = Files.list(root)) {
            for (Path path : paths.limit(1000).toList()) {
                if ((path.getFileName().toString().matches("image-[a-f0-9]{64}\\.enc")
                                || path.getFileName().toString().matches("incoming-.*\\.tmp"))
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(path)
                                .toInstant()
                                .isBefore(Instant.now().minusSeconds(86400))) Files.deleteIfExists(path);
            }
        }
    }

    private Path path(String id) {
        if (!id.matches("image-[a-f0-9]{64}")) throw new IllegalStateException("MEDIA_ID_INVALID");
        return root.resolve(id + ".enc");
    }

    static String imageType(byte[] bytes) throws Exception {
        try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalStateException("MEDIA_IMAGE_INVALID");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("png", "jpeg", "gif").contains(format))
                    throw new IllegalStateException("MEDIA_IMAGE_UNSUPPORTED");
                reader.setInput(stream);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > 40000000 || pixels <= 0) throw new IllegalStateException("MEDIA_IMAGE_INVALID");
                return "image/" + format;
            } finally {
                reader.dispose();
            }
        }
    }

    private static IllegalStateException safe(Throwable error) {
        return new IllegalStateException(
                error.getMessage() != null && error.getMessage().matches("MEDIA_[A-Z_]+")
                        ? error.getMessage()
                        : "MEDIA_STORAGE_FAILED");
    }
}
