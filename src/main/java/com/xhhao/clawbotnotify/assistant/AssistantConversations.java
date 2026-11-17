package com.xhhao.clawbotnotify.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.message.ModelMessage;

/** Prune complete turns, keeping tool calls and their results together. All bodies stay encrypted. */
@Component
public class AssistantConversations {
    private final CredentialStore secrets;
    private final ObjectMapper json = new ObjectMapper();

    public AssistantConversations(CredentialStore secrets) {
        this.secrets = secrets;
    }

    public Mono<List<ModelMessage>> messages(WechatBinding binding, AssistantSettings.Config config) {
        return ensure(binding).map(data -> {
            var result = new ArrayList<ModelMessage>();
            for (var turn : turns(data, config)) result.addAll(turn.messages());
            return result;
        });
    }

    public Mono<Void> append(
            WechatBinding binding,
            AssistantSettings.Config config,
            String command,
            String input,
            List<ModelMessage> response) {
        return secrets.update(reference(binding), data -> {
            var turns = turns(data, config);
            if (turns.stream().anyMatch(turn -> turn.command().equals(command))) return;
            var messages = new ArrayList<ModelMessage>();
            messages.add(ModelMessage.user(input));
            messages.addAll(response);
            turns.add(new Turn(command, messages));
            trim(turns, config);
            data.put("turns", encode(turns));
            data.put("updatedAt", Instant.now().toString());
        });
    }

    public Mono<Void> reset(WechatBinding binding) {
        return ensure(binding).then(secrets.update(reference(binding), data -> data.clear()));
    }

    public Mono<Void> trimStored(String reference, AssistantSettings.Config config) {
        if (!reference.matches("clawbot-chat-[a-f0-9]{64}"))
            return Mono.error(new IllegalStateException("AI_HISTORY_INVALID"));
        return secrets.update(reference, data -> {
            if (data.containsKey("turns")) data.put("turns", encode(turns(data, config)));
        });
    }

    private Mono<Map<String, String>> ensure(WechatBinding binding) {
        String ref = reference(binding);
        return secrets.create(ref, Map.of()).then(secrets.read(ref)).onErrorResume(error -> secrets.read(ref));
    }

    private ArrayList<Turn> turns(Map<String, String> data, AssistantSettings.Config config) {
        try {
            if (!data.containsKey("turns")) return new ArrayList<>();
            ArrayList<Turn> turns = json.readValue(data.get("turns"), new TypeReference<>() {});
            trim(turns, config);
            return turns;
        } catch (Exception error) {
            throw new IllegalStateException("AI_HISTORY_INVALID");
        }
    }

    private void trim(ArrayList<Turn> turns, AssistantSettings.Config config) {
        while (!turns.isEmpty() && AssistantContextBudget.size(turns) > config.getMaxContextChars())
            turns.removeFirst();
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("AI_HISTORY_INVALID");
        }
    }

    private String reference(WechatBinding binding) {
        var spec = binding.getSpec();
        return "clawbot-chat-"
                + StableIds.hash(
                        Long.toString(spec.getGeneration()),
                        spec.getBotId(),
                        spec.getOwnerWechatUserId(),
                        spec.getHaloUsername());
    }

    public record Turn(String command, List<ModelMessage> messages) {}
}
