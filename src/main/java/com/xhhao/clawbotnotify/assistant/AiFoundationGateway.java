package com.xhhao.clawbotnotify.assistant;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.GenerateTextRequest;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/** Model credentials and provider selection remain in AI Foundation. */
@Component
public class AiFoundationGateway {
    private final ExtensionGetter extensions;

    public AiFoundationGateway(ExtensionGetter extensions) {
        this.extensions = extensions;
    }

    public Mono<Boolean> available(String modelName) {
        return extensions
                .getEnabledExtension(AiModelService.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_FOUNDATION_UNAVAILABLE")))
                .flatMap(service -> service.languageModel(modelName))
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_MODEL_UNAVAILABLE")))
                .map(model -> true)
                .onErrorMap(error -> "AI_FOUNDATION_UNAVAILABLE".equals(error.getMessage())
                        ? error
                        : new IllegalStateException("AI_MODEL_UNAVAILABLE"));
    }

    public Mono<GenerateTextResult> generate(String modelName, GenerateTextRequest request) {
        return extensions
                .getEnabledExtension(AiModelService.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_FOUNDATION_UNAVAILABLE")))
                .flatMap(service -> service.languageModel(modelName))
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_MODEL_UNAVAILABLE")))
                .flatMap(model -> {
                    if (!model.capabilities().reasoningHistorySupported() && request.getMessages() != null) {
                        request.setMessages(request.getMessages().stream()
                                .map(message -> new run.halo.aifoundation.message.ModelMessage(
                                        message.getRole(),
                                        message.getContent().stream()
                                                .filter(part -> !run.halo.aifoundation.part.PartType.REASONING.equals(
                                                        part.getType()))
                                                .toList()))
                                .filter(message -> !message.getContent().isEmpty())
                                .toList());
                    }
                    return model.generateText(request);
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("AI_EMPTY_RESULT")));
    }
}
