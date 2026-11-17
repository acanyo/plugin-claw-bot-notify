package com.xhhao.clawbotnotify.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.BotMomentPublisher;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Photo workflows freeze inbox identities in the proposal; uploads and final writes have durable claims. */
@Component
public class AssistantMediaTools {
    private static final Set<String> TOOLS =
            Set.of("wechat_media_list", "attachment_upload", "moment_create_images", "post_set_cover");
    private final WechatMediaInbox inbox;
    private final MediaFileStore files;
    private final HaloMediaClient halo;
    private final CredentialStore secrets;
    private final AssistantDrafts drafts;
    private final BotMomentPublisher moments;
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public AssistantMediaTools(
            WechatMediaInbox inbox,
            MediaFileStore files,
            HaloMediaClient halo,
            CredentialStore secrets,
            AssistantDrafts drafts,
            BotMomentPublisher moments) {
        this.inbox = inbox;
        this.files = files;
        this.halo = halo;
        this.secrets = secrets;
        this.drafts = drafts;
        this.moments = moments;
    }

    public static boolean supports(String tool) {
        return TOOLS.contains(tool);
    }

    public Mono<Object> execute(
            WechatBinding binding, InboundCommand command, String tool, Map<String, Object> input, boolean writes) {
        if (tool.equals("wechat_media_list"))
            return inbox.list(binding, command).map(images -> (Object) Map.of("images", images));
        if (!writes) return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
        return inbox.list(binding, command).flatMap(available -> {
            Object requested = input.get("mediaIds");
            if (!(requested instanceof List<?> ids)
                    || ids.isEmpty()
                    || ids.size() > 9
                    || ids.stream().anyMatch(id -> !(id instanceof String)))
                return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
            var images = ids.stream()
                    .map(id -> available.stream()
                            .filter(image -> image.id().equals(id))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("MEDIA_EXPIRED")))
                    .distinct()
                    .toList();
            if (tool.equals("post_set_cover") && images.size() != 1)
                return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
            var values = new LinkedHashMap<String, Object>();
            values.put("images", json.convertValue(images, List.class));
            if (tool.equals("moment_create_images")) {
                String content = String.valueOf(input.getOrDefault("content", ""));
                if (content.isBlank() || content.codePointCount(0, content.length()) > 2000)
                    return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
                String visible = String.valueOf(input.getOrDefault("visible", "PUBLIC"));
                if (!Set.of("PUBLIC", "PRIVATE").contains(visible))
                    return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
                values.put("content", content);
                values.put("visible", visible);
            }
            if (!tool.equals("post_set_cover")) return drafts.stage(command, tool, values, null, false);
            String name = String.valueOf(input.getOrDefault("name", ""));
            return token(binding).flatMap(token -> halo.post(token, name)).flatMap(post -> {
                if (!name.equals(post.path("metadata").path("name").asText())
                        || post.path("spec").path("deleted").asBoolean()
                        || post.path("metadata").hasNonNull("deletionTimestamp"))
                    return Mono.error(new IllegalStateException("MEDIA_TARGET_UNAVAILABLE"));
                values.put("name", name);
                values.put("title", post.path("spec").path("title").asText());
                values.put("previousCover", post.path("spec").path("cover").asText());
                return drafts.stage(command, tool, values, StableIds.hash(post.toString()), false);
            });
        });
    }

    public Mono<Object> confirmed(WechatBinding binding, InboundCommand command, AssistantDrafts.Draft draft) {
        return token(binding).flatMap(token -> {
            List<MediaFileStore.Image> images = json.convertValue(
                    draft.input().get("images"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<MediaFileStore.Image>>() {});
            if (images.isEmpty() || images.stream().anyMatch(i -> i.expiresAt().isBefore(Instant.now())))
                return Mono.error(new IllegalStateException("MEDIA_EXPIRED"));
            Mono<com.fasterxml.jackson.databind.JsonNode> snapshot =
                    draft.tool().equals("post_set_cover")
                            ? halo.post(token, String.valueOf(draft.input().get("name")))
                                    .map(post -> {
                                        if (!StableIds.hash(post.toString()).equals(draft.fingerprint()))
                                            throw new IllegalStateException("MEDIA_TARGET_CHANGED");
                                        return post;
                                    })
                            : Mono.just(json.createObjectNode());
            return snapshot.flatMap(post -> claim(command, draft).flatMap(cached -> {
                if (!cached.isEmpty()) return Mono.just(jsonValue(cached));
                return Flux.fromIterable(images)
                        .concatMap(image -> upload(binding, command, token, image))
                        .collectList()
                        .flatMap(attachments -> {
                            if (draft.tool().equals("attachment_upload"))
                                return Mono.just((Object) Map.of("attachments", attachments));
                            if (draft.tool().equals("post_set_cover"))
                                return halo.setCover(
                                                token,
                                                post,
                                                attachments.getFirst().url())
                                        .map(result -> (Object) Map.of(
                                                "name",
                                                result.path("metadata")
                                                        .path("name")
                                                        .asText(),
                                                "cover",
                                                attachments.getFirst().url(),
                                                "attachments",
                                                attachments));
                            String name = "clawbot-"
                                    + StableIds.hash(command.getMetadata().getName(), draft.tool())
                                            .substring(0, 32);
                            return moments.publish(
                                            token,
                                            name,
                                            String.valueOf(draft.input().get("content")),
                                            String.valueOf(draft.input().get("visible")),
                                            attachments,
                                            draft.author())
                                    .map(result -> {
                                        var resource = result.has("moment") ? result.path("moment") : result;
                                        if (!name.equals(resource.path("metadata")
                                                .path("name")
                                                .asText())) throw new IllegalStateException("MEDIA_RESULT_UNKNOWN");
                                        return (Object) Map.of(
                                                "name",
                                                name,
                                                "attachments",
                                                attachments,
                                                "visible",
                                                draft.input().get("visible"),
                                                "owner",
                                                result.path("spec")
                                                        .path("owner")
                                                        .asText());
                                    });
                        })
                        .flatMap(result -> secrets.update(
                                        command.getSpec().getBodyRef(),
                                        values -> values.put("mutationResult", inbox.encode(result)))
                                .thenReturn(result));
            }));
        });
    }

    private Mono<String> claim(InboundCommand command, AssistantDrafts.Draft draft) {
        String ref = command.getSpec().getBodyRef();
        String fingerprint = com.xhhao.clawbotnotify.assistant.review.DraftReview.version(draft);
        return secrets.read(ref).flatMap(data -> {
            if (data.containsKey("mutation")) {
                if (fingerprint.equals(data.get("mutation")) && data.containsKey("mutationResult"))
                    return Mono.just(data.get("mutationResult"));
                return Mono.error(new IllegalStateException("AI_WRITE_ALREADY_CLAIMED"));
            }
            return secrets.update(ref, values -> {
                        if (values.containsKey("mutation")) throw new IllegalStateException("AI_WRITE_ALREADY_CLAIMED");
                        values.put("mutation", fingerprint);
                        values.put("mutationTool", draft.tool());
                        values.put("mutationName", String.valueOf(draft.input().getOrDefault("name", draft.tool())));
                    })
                    .thenReturn("");
        });
    }

    private Mono<HaloMediaClient.Attachment> upload(
            WechatBinding binding, InboundCommand command, String token, MediaFileStore.Image image) {
        String key = "uploaded:" + image.id();
        return secrets.read(binding.getSpec().getCredentialRef()).flatMap(data -> {
            if (data.containsKey(key)) {
                try {
                    return Mono.just(json.readValue(data.get(key), HaloMediaClient.Attachment.class));
                } catch (Exception error) {
                    return Mono.error(new IllegalStateException("MEDIA_STORAGE_FAILED"));
                }
            }
            return files.read(image)
                    .flatMap(bytes -> secrets.update(command.getSpec().getBodyRef(), values -> {
                                String claim = "upload:" + image.id();
                                if (values.containsKey(claim))
                                    throw new IllegalStateException("AI_WRITE_ALREADY_CLAIMED");
                                values.put(claim, "DISPATCHED");
                            })
                            .then(Mono.defer(() -> halo.upload(token, image, bytes))))
                    .flatMap(attachment -> secrets.update(command.getSpec().getBodyRef(), values -> {
                                values.put(key, inbox.encode(attachment));
                                values.put(
                                        "mediaCompleted",
                                        values.getOrDefault("mediaCompleted", "") + attachment.name() + " ");
                            })
                            .then(secrets.update(
                                    binding.getSpec().getCredentialRef(),
                                    values -> values.put(key, inbox.encode(attachment))))
                            .thenReturn(attachment));
        });
    }

    private Mono<String> token(WechatBinding binding) {
        return secrets.read(binding.getSpec().getCredentialRef()).map(values -> {
            String token = values.get("haloPat");
            if (token == null || token.isBlank()) throw new IllegalStateException("CREDENTIAL_UNAVAILABLE");
            return token;
        });
    }

    private Object jsonValue(String value) {
        try {
            return json.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("MEDIA_RESULT_UNKNOWN");
        }
    }
}
