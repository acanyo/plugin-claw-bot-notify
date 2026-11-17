package com.xhhao.clawbotnotify.command;

import static run.halo.app.extension.index.query.Queries.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

/** Only a persisted outbound message in this binding can select a comment. No number extraction from quote text. */
@Component
public class QuotedCommentResolver {
    private final ReactiveExtensionClient resources;

    public QuotedCommentResolver(ReactiveExtensionClient resources) {
        this.resources = resources;
    }

    public record Target(String commentName, String targetReplyName, String commandName, String messageId) {}

    public Mono<Target> resolve(WechatBinding binding, JsonNode message) {
        Set<String> ids = new HashSet<>();
        Set<String> bodies = new HashSet<>();
        for (var item : message.path("item_list")) {
            var ref = item.path("ref_msg");
            if (!ref.isObject()) continue;
            String id = identifier(ref.path("svr_id"));
            if (id == null) id = identifier(ref.path("message_item").path("msg_id"));
            if (id != null) ids.add(id);
            var body = ref.path("message_item").path("text_item").path("text");
            if (body.isTextual() && !body.asText().isBlank()) bodies.add(body.asText());
        }
        if (ids.size() > 1 || bodies.size() > 1) return Mono.empty();
        String id = ids.stream().findFirst().orElse(null);
        if (id == null && bodies.isEmpty()) return Mono.empty();
        var scope = equal("spec.generation", binding.getSpec().getGeneration())
                .and(equal("spec.targetUserId", binding.getSpec().getOwnerWechatUserId()))
                .and(not(equal("spec.kind", "AI_PROGRESS")))
                .and(in("spec.state", OutboundTask.State.ACCEPTED.name(), OutboundTask.State.UNKNOWN.name()));
        var byId = id == null ? Mono.<Target>empty() : find(scope.and(equal("spec.upstreamMessageId", id)), id);
        return byId.switchIfEmpty(Mono.defer(() -> bodies.isEmpty()
                ? Mono.empty()
                : find(
                        scope.and(equal(
                                "spec.quoteTextHash",
                                fingerprint(bodies.iterator().next()))),
                        id)));
    }

    private Mono<Target> find(run.halo.app.extension.index.query.Condition query, String id) {
        return resources
                .listBy(
                        OutboundTask.class,
                        ListOptions.builder().fieldQuery(query).build(),
                        PageRequestImpl.ofSize(20))
                .flatMap(page -> {
                    if (page.hasNext() || page.getItems().isEmpty()) return Mono.empty();
                    var targets = page.getItems().stream()
                            .map(t -> new Target(
                                    t.getSpec().getCommentName(), t.getSpec().getTargetReplyName(), null, null))
                            .distinct()
                            .toList();
                    if (targets.size() != 1) return Mono.empty();
                    if (targets.getFirst().commentName() == null
                            && (page.getItems().size() != 1
                                    || page.getItems().getFirst().getSpec().getCommandName() == null))
                        return Mono.empty();
                    // Only the exact quoted receipt can supply the body awaiting moderation.
                    var task = page.getItems().getFirst();
                    String command =
                            page.getItems().size() == 1 ? task.getSpec().getCommandName() : null;
                    var target = new Target(
                            targets.getFirst().commentName(), targets.getFirst().targetReplyName(), command, id);
                    if (target.commentName() == null)
                        return resources
                                .fetch(InboundCommand.class, command)
                                .filter(source -> source.getSpec().getType() == InboundCommand.Type.AI
                                        || source.getSpec().getType() == InboundCommand.Type.AI_PREVIEW
                                        || source.getSpec().getType() == InboundCommand.Type.AI_MEDIA)
                                .map(source -> source.getSpec().getType() == InboundCommand.Type.AI_PREVIEW
                                        ? new Target(
                                                null, null, source.getSpec().getPreviewOf(), id)
                                        : target);
                    return Mono.just(target);
                });
    }

    public static boolean hasQuote(JsonNode message) {
        for (var item : message.path("item_list")) if (item.path("ref_msg").isObject()) return true;
        return false;
    }

    private static String identifier(JsonNode value) {
        if (value.isIntegralNumber() && value.bigIntegerValue().signum() >= 0
                || value.isTextual() && !value.asText().isBlank()) return value.asText();
        return null;
    }

    public static String fingerprint(String body) {
        return StableIds.hash(body.replace("\r\n", "\n").strip());
    }
}
