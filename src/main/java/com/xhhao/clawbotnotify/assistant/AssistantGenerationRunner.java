package com.xhhao.clawbotnotify.assistant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.chat.FinishReason;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.chat.GenerationStep;
import run.halo.aifoundation.chat.StopCondition;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.aifoundation.message.ModelMessageRole;
import run.halo.aifoundation.part.PartType;
import run.halo.aifoundation.tool.ToolDefinition;
import run.halo.aifoundation.tool.ToolError;

/** Continues tool rounds by context capacity, independently of the SDK's per-generation step ceiling. */
@Component
public class AssistantGenerationRunner {
    private final AiFoundationGateway model;

    public AssistantGenerationRunner(AiFoundationGateway model) {
        this.model = model;
    }

    public Mono<GenerateTextResult> generate(
            String modelName,
            String system,
            List<ModelMessage> history,
            String input,
            List<ToolDefinition> tools,
            int maximumCharacters) {
        return Mono.defer(() -> {
            var run = new Run(modelName, system, history, input, tools, maximumCharacters);
            return run.next()
                    .expand(result -> completedToolRound(result) ? run.next() : Mono.empty(), 1)
                    .last()
                    .map(run::complete);
        });
    }

    private static boolean completedToolRound(GenerateTextResult result) {
        var finish = result.getFinishReason();
        if (finish == FinishReason.LENGTH || finish == FinishReason.ERROR || finish == FinishReason.CONTENT_FILTER)
            return false;
        var messages = result.getResponseMessages();
        if (messages == null || messages.isEmpty() || messages.getLast().getRole() != ModelMessageRole.TOOL)
            return false;
        var calls = new HashSet<String>();
        var responses = new HashSet<String>();
        for (var message : messages) {
            if (message.getContent() == null) continue;
            for (var part : message.getContent()) {
                if (PartType.TOOL_CALL.equals(part.getType())) calls.add(part.getToolCallId());
                if (PartType.isToolResponse(part.getType())) responses.add(part.getToolCallId());
            }
        }
        return !calls.isEmpty() && !calls.contains(null) && responses.containsAll(calls);
    }

    private final class Run {
        private final String modelName;
        private final String system;
        private final List<ModelMessage> history;
        private final List<ToolDefinition> tools;
        private final int maximumCharacters;
        private final List<ModelMessage> current = new ArrayList<>();
        private final List<ModelMessage> generated = new ArrayList<>();
        private final List<GenerationStep> steps = new ArrayList<>();
        private final List<ToolError> errors = new ArrayList<>();
        private final List<Long> roundDurationMs = new ArrayList<>();

        Run(
                String modelName,
                String system,
                List<ModelMessage> history,
                String input,
                List<ToolDefinition> tools,
                int maximumCharacters) {
            this.modelName = modelName;
            this.system = system;
            this.history = history;
            this.tools = tools;
            this.maximumCharacters = maximumCharacters;
            current.add(ModelMessage.user(input));
        }

        Mono<GenerateTextResult> next() {
            return Mono.defer(() -> {
                var messages = AssistantContextBudget.window(system, tools, history, current, maximumCharacters);
                var request = GenerateTextRequest.builder()
                        .system(system)
                        .messages(messages)
                        .tools(tools)
                        .maxRetries(0)
                        .parallelToolCalls(false)
                        // A non-null condition enables SDK tool execution; end this SDK batch after one round.
                        // The outer run continues with its exact call/result pairs, without a total step quota.
                        .stopWhen(StopCondition.stepCountIs(1))
                        .build();
                long start = System.nanoTime();
                return model.generate(modelName, request).doOnNext(result -> {
                    roundDurationMs.add((System.nanoTime() - start) / 1_000_000);
                    record(result);
                });
            });
        }

        void record(GenerateTextResult result) {
            var response = result.getResponseMessages();
            if ((response == null || response.isEmpty())
                    && result.getText() != null
                    && !result.getText().isBlank()) response = List.of(ModelMessage.assistant(result.getText()));
            if (response != null) {
                generated.addAll(response);
                current.addAll(response);
            }
            if (result.getSteps() != null && !result.getSteps().isEmpty()) steps.addAll(result.getSteps());
            else
                steps.add(GenerationStep.builder()
                        .stepIndex(steps.size())
                        .text(result.getText())
                        .finishReason(result.getFinishReason())
                        .toolCalls(result.getToolCalls())
                        .responseMessages(response)
                        .build());
            if (result.getToolErrors() != null) errors.addAll(result.getToolErrors());
        }

        GenerateTextResult complete(GenerateTextResult last) {
            return GenerateTextResult.builder()
                    .text(last.getText())
                    .finishReason(last.getFinishReason())
                    .rawFinishReason(last.getRawFinishReason())
                    .steps(List.copyOf(steps))
                    .responseMessages(List.copyOf(generated))
                    .toolCalls(last.getToolCalls())
                    .toolResults(last.getToolResults())
                    .toolErrors(List.copyOf(errors))
                    .providerMetadata(Map.of("assistantRoundDurationMs", List.copyOf(roundDurationMs)))
                    .build();
        }
    }
}
