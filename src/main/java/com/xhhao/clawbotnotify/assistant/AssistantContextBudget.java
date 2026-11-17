package com.xhhao.clawbotnotify.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.message.ModelMessageRole;
import run.halo.aifoundation.tool.ToolDefinition;

/** Counts Unicode characters rather than pretending different providers share a tokenizer. */
public final class AssistantContextBudget {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AssistantContextBudget() {}

    public static long size(Object value) {
        try {
            String text = JSON.writeValueAsString(value);
            return text.codePointCount(0, text.length());
        } catch (Exception error) {
            throw new IllegalStateException("AI_CONTEXT_INVALID");
        }
    }

    public static long overhead(String system, List<ToolDefinition> tools) {
        long count = size(system);
        if (tools != null) {
            for (var tool : tools) {
                count += size(Map.of(
                        "name", tool.getName(),
                        "description", tool.getDescription() == null ? "" : tool.getDescription(),
                        "parameters", tool.getInputSchema() == null ? Map.of() : tool.getInputSchema()));
            }
        }
        return count;
    }

    /** Evict complete old turns only. Current input and its tool pairs are never cut. */
    public static List<ModelMessage> window(
            String system,
            List<ToolDefinition> tools,
            List<ModelMessage> history,
            List<ModelMessage> current,
            int maximumCharacters) {
        long required = overhead(system, tools) + size(current);
        if (required > maximumCharacters) throw new IllegalStateException("AI_CONTEXT_LIMIT");
        var retained = new ArrayList<>(history);
        while (!retained.isEmpty() && required + size(retained) > maximumCharacters) {
            int nextTurn = 1;
            while (nextTurn < retained.size() && retained.get(nextTurn).getRole() != ModelMessageRole.USER) nextTurn++;
            retained.subList(0, nextTurn).clear();
        }
        retained.addAll(current);
        return List.copyOf(retained);
    }
}
