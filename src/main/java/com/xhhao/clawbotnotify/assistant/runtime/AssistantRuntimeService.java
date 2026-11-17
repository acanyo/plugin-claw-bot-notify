package com.xhhao.clawbotnotify.assistant.runtime;

import com.xhhao.clawbotnotify.api.AssistantRuntimeView;
import com.xhhao.clawbotnotify.assistant.AiFoundationGateway;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AssistantRuntimeService {
    private final AssistantSettings settings;
    private final AiFoundationGateway model;
    private final AssistantTools tools;
    private final AssistantMaintenance maintenance;

    public AssistantRuntimeService(
            AssistantSettings settings,
            AiFoundationGateway model,
            AssistantTools tools,
            AssistantMaintenance maintenance) {
        this.settings = settings;
        this.model = model;
        this.tools = tools;
        this.maintenance = maintenance;
    }

    public Mono<AssistantRuntimeView> status() {
        return settings.get()
                .flatMap(config -> Mono.zip(
                                probe(
                                        config.getModelName() == null
                                                        || config.getModelName().isBlank()
                                                ? "默认语言模型"
                                                : config.getModelName(),
                                        () -> model.available(config.getModelName())),
                                probe("Halo CLI", tools::available))
                        .map(pair -> new AssistantRuntimeView(
                                config.isEnabled(),
                                config.isAllowWrites(),
                                Instant.now(),
                                pair.getT1(),
                                pair.getT2(),
                                config.getRetentionDays(),
                                config.getDraftMinutes(),
                                maintenance.lastRunAt(),
                                maintenance.lastCleaned(),
                                maintenance.errorCode())));
    }

    private Mono<AssistantRuntimeView.Dependency> probe(String name, Supplier<Mono<Boolean>> check) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return Mono.defer(check)
                    .timeout(Duration.ofSeconds(45))
                    .map(available -> new AssistantRuntimeView.Dependency(
                            name,
                            available,
                            (System.nanoTime() - start) / 1_000_000,
                            available ? null : "AI_MODEL_UNAVAILABLE"))
                    .onErrorResume(error -> Mono.just(new AssistantRuntimeView.Dependency(
                            name, false, (System.nanoTime() - start) / 1_000_000, AssistantTools.safeCode(error))));
        });
    }
}
