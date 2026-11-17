package com.xhhao.clawbotnotify.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xhhao.clawbotnotify.assistant.AssistantAuthority;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/** Encrypted per-binding inbox, scoped by bot/owner/generation. It never uploads images to Halo on receipt. */
@Component
public class WechatMediaInbox {
    private final CredentialStore secrets;
    private final MediaFileStore files;
    private final WechatMediaDownloader downloader;
    private final AssistantAuthority authority;
    private final ReactiveExtensionClient resources;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public WechatMediaInbox(
            CredentialStore secrets,
            MediaFileStore files,
            WechatMediaDownloader downloader,
            AssistantAuthority authority,
            ReactiveExtensionClient resources) {
        this.secrets = secrets;
        this.files = files;
        this.downloader = downloader;
        this.authority = authority;
        this.resources = resources;
    }

    public static boolean hasImages(JsonNode message) {
        for (var item : message.path("item_list")) if (item.path("type").asInt() == 2) return true;
        return false;
    }

    public Mono<InboundCommand> receive(WechatBinding binding, InboundCommand command, JsonNode message) {
        if (!hasImages(message)) return Mono.just(command);
        return authority
                .check(binding)
                .then(secrets.read(command.getSpec().getBodyRef()))
                .flatMap(values -> {
                    if (values.containsKey("media")) return Mono.just(command);
                    var items = new ArrayList<JsonNode>();
                    for (var item : message.path("item_list"))
                        if (item.path("type").asInt() == 2) items.add(item);
                    if (items.size() > 9) return Mono.error(new IllegalStateException("MEDIA_COUNT_LIMIT"));
                    return Flux.range(0, items.size())
                            .concatMap(index -> {
                                String id = "image-"
                                        + StableIds.hash(
                                                Long.toString(binding.getSpec().getGeneration()),
                                                binding.getSpec().getBotId(),
                                                binding.getSpec().getOwnerWechatUserId(),
                                                command.getMetadata().getName(),
                                                Integer.toString(index));
                                return downloader.download(items.get(index)).flatMap(bytes -> files.save(id, bytes));
                            })
                            .collectList()
                            .flatMap(images -> secrets.update(
                                            command.getSpec().getBodyRef(), data -> data.put("media", encode(images)))
                                    .then(secrets.update(binding.getSpec().getCredentialRef(), data -> {
                                        List<MediaFileStore.Image> recent =
                                                decode(data.getOrDefault("recentMedia", "[]"));
                                        var updated = new ArrayList<>(images);
                                        for (var image : recent)
                                            if (image.expiresAt().isAfter(Instant.now())
                                                    && updated.stream().noneMatch(i -> i.id().equals(image.id())))
                                                updated.add(image);
                                        var retained =
                                                updated.stream().limit(18).toList();
                                        data.put("recentMedia", encode(retained));
                                        data.keySet()
                                                .removeIf(key -> key.startsWith("uploaded:")
                                                        && retained.stream()
                                                                .noneMatch(i -> key.equals("uploaded:" + i.id())));
                                    }))
                                    .thenReturn(command));
                })
                .onErrorResume(error -> secrets.update(command.getSpec().getBodyRef(), data -> {
                            data.put("aiResponse", "图片接收失败（" + safe(error) + "）。未上传或发布，请重新发送图片。");
                            data.put("aiState", "REJECTED");
                            data.put("aiError", safe(error));
                        })
                        .then(Mono.defer(() -> {
                            command.getSpec().setWriteState(InboundCommand.WriteState.REJECTED);
                            command.getSpec().setErrorCode(safe(error));
                            return resources.update(command);
                        })));
    }

    public Mono<InboundCommand> acknowledge(InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef()).flatMap(values -> {
            if (values.containsKey("aiResponse")) return Mono.just(command);
            List<MediaFileStore.Image> images = decode(values.getOrDefault("media", "[]"));
            String text = "已收到 " + images.size() + " 张图片，已加密暂存，尚未上传 Halo。\n"
                    + "引用本消息发送“上传到附件”“用这些图片发私密瞬间：正文”或“设为文章 <name> 的封面”。\n图片暂存 24 小时，发布前会先生成预览。";
            return secrets.update(command.getSpec().getBodyRef(), data -> {
                        data.put("aiResponse", text);
                        data.put("aiState", "SUCCEEDED");
                    })
                    .then(Mono.defer(() -> {
                        command.getSpec().setWriteState(InboundCommand.WriteState.SUCCEEDED);
                        return resources.update(command);
                    }));
        });
    }

    public Mono<List<MediaFileStore.Image>> list(WechatBinding binding, InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef())
                .flatMap(values -> values.containsKey("media")
                        ? Mono.just(decode(values.get("media")))
                        : secrets.read(binding.getSpec().getCredentialRef())
                                .map(data -> decode(data.getOrDefault("recentMedia", "[]"))))
                .map(images -> images.stream()
                        .filter(i -> i.expiresAt().isAfter(Instant.now()))
                        .toList());
    }

    public Mono<Void> cleanup() {
        return files.cleanup()
                .then(resources
                        .fetch(WechatBinding.class, "primary")
                        .filter(binding -> binding.getSpec().getCredentialRef() != null)
                        .flatMap(binding -> secrets.update(binding.getSpec().getCredentialRef(), data -> {
                            var images = decode(data.getOrDefault("recentMedia", "[]")).stream()
                                    .filter(image -> image.expiresAt().isAfter(Instant.now()))
                                    .limit(18)
                                    .toList();
                            data.put("recentMedia", encode(images));
                            data.keySet()
                                    .removeIf(key -> key.startsWith("uploaded:")
                                            && images.stream().noneMatch(i -> key.equals("uploaded:" + i.id())));
                        }))
                        .then());
    }

    public List<MediaFileStore.Image> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<List<MediaFileStore.Image>>() {});
        } catch (Exception error) {
            throw new IllegalStateException("MEDIA_STORAGE_FAILED");
        }
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("MEDIA_STORAGE_FAILED");
        }
    }

    private static String safe(Throwable error) {
        return error.getMessage() != null && error.getMessage().matches("(?:MEDIA_|HALO_|CREDENTIAL_)[A-Z_]+")
                ? error.getMessage()
                : "MEDIA_RECEIVE_FAILED";
    }
}
