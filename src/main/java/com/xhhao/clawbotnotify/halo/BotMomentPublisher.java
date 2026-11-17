package com.xhhao.clawbotnotify.halo;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.media.HaloMediaClient;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** All assistant moment creation shares the Bot author, including the general CLI bridge. */
@Component
public class BotMomentPublisher {
    private final MomentAuthors identity;
    private final HaloMediaClient halo;

    public BotMomentPublisher(MomentAuthors identity, HaloMediaClient halo) {
        this.identity = identity;
        this.halo = halo;
    }

    public Mono<JsonNode> publish(
            String token,
            String name,
            String raw,
            String visible,
            List<HaloMediaClient.Attachment> images,
            MomentAuthors.Author author) {
        return identity.verify(author)
                .flatMap(owner -> halo.moment(token, name, owner.username(), raw, visible, images)
                        .map(result -> verify(result, name, owner.username())));
    }

    public static boolean cliCreate(List<String> args) {
        return args.size() >= 2 && args.subList(0, 2).equals(List.of("moment", "create"));
    }

    /** Preserve the installed CLI's moment options without invoking its current-user creation endpoint. */
    public Mono<JsonNode> publishCli(String token, String command, List<String> args, MomentAuthors.Author author) {
        return Mono.defer(() -> {
            if (!cliCreate(args)) return Mono.error(new IllegalStateException("CLI_COMMAND_INVALID"));
            var options = new LinkedHashMap<String, String>();
            for (int i = 2; i < args.size(); i++) {
                String argument = args.get(i);
                int equals = argument.indexOf('=');
                String flag = equals < 0 ? argument : argument.substring(0, equals);
                if (Set.of("--json", "--yes").contains(flag)) continue;
                if (!Set.of("--profile", "--name", "--content", "--visible", "--tags", "--release-time", "--approved")
                        .contains(flag)) throw new IllegalStateException("CLI_CONTRACT_CHANGED");
                String value = equals < 0 ? (++i < args.size() ? args.get(i) : null) : argument.substring(equals + 1);
                if (value == null) throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
                options.put(flag, value);
            }
            String name = options.getOrDefault(
                    "--name",
                    "clawbot-" + StableIds.hash(command, "moment_create").substring(0, 32));
            String html = options.get("--content");
            String visible = options.getOrDefault("--visible", "PUBLIC");
            String approved = options.getOrDefault("--approved", "true");
            if (!name.matches("[a-z0-9][a-z0-9.-]{0,252}")
                    || html == null
                    || html.isBlank()
                    || !Set.of("PUBLIC", "PRIVATE").contains(visible)
                    || !Set.of("true", "false").contains(approved))
                throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
            var spec = new LinkedHashMap<String, Object>();
            spec.put("visible", visible);
            spec.put("approved", Boolean.parseBoolean(approved));
            spec.put(
                    "releaseTime",
                    options.containsKey("--release-time")
                            ? OffsetDateTime.parse(options.get("--release-time"))
                                    .toInstant()
                                    .toString()
                            : Instant.now().toString());
            spec.put("content", Map.of("raw", html, "html", html));
            if (options.containsKey("--tags"))
                spec.put(
                        "tags",
                        Arrays.stream(options.get("--tags").split(","))
                                .map(String::strip)
                                .filter(value -> !value.isEmpty())
                                .distinct()
                                .toList());
            return identity.verify(author).flatMap(owner -> {
                spec.put("owner", owner.username());
                return halo.momentResource(
                                token,
                                Map.of(
                                        "apiVersion",
                                        "moment.halo.run/v1alpha1",
                                        "kind",
                                        "Moment",
                                        "metadata",
                                        Map.of("name", name),
                                        "spec",
                                        spec))
                        .map(result -> verify(result, name, owner.username()));
            });
        });
    }

    private JsonNode verify(JsonNode result, String name, String expectedOwner) {
        var resource = result.has("moment") ? result.path("moment") : result;
        if (!name.equals(resource.path("metadata").path("name").asText())
                || !expectedOwner.equals(resource.path("spec").path("owner").asText()))
            throw new IllegalStateException("HALO_BOT_AUTHOR_UNCONFIRMED");
        return resource;
    }
}
