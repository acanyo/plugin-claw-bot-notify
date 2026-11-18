package com.xhhao.clawbotnotify.assistant.runtime;

import com.xhhao.clawbotnotify.api.AssistantRuntimeView;
import com.xhhao.clawbotnotify.assistant.AiFoundationGateway;
import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime;
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
    private final ManagedCliRuntime cliRuntime;

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantRuntimeService(
            AssistantSettings settings,
            AiFoundationGateway model,
            AssistantTools tools,
            AssistantMaintenance maintenance,
            ManagedCliRuntime cliRuntime) {
        this.settings = settings;
        this.model = model;
        this.tools = tools;
        this.maintenance = maintenance;
        this.cliRuntime = cliRuntime;
    }

    public AssistantRuntimeService(
            AssistantSettings settings,
            AiFoundationGateway model,
            AssistantTools tools,
            AssistantMaintenance maintenance) {
        this(settings, model, tools, maintenance, null);
    }

    public Mono<AssistantRuntimeView> retryCli() {
        if (cliRuntime != null) cliRuntime.retry();
        return status();
    }

    public Mono<AssistantRuntimeView> status() {
        if (cliRuntime != null) cliRuntime.start();
        return settings.get()
                .flatMap(config -> Mono.zip(
                                probe(
                                        config.getModelName() == null
                                                        || config.getModelName().isBlank()
                                                ? "默认语言模型"
                                                : config.getModelName(),
                                        () -> model.available(config.getModelName())),
                                cliProbe())
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
                                maintenance.errorCode(),
                                cliRuntime == null ? null : cliRuntime.status())));
    }

    private Mono<AssistantRuntimeView.Dependency> cliProbe() {
        if (cliRuntime != null) {
            var setup = cliRuntime.status();
            if (!java.util.Set.of("READY", "EXTERNAL").contains(setup.state()))
                return Mono.just(new AssistantRuntimeView.Dependency(
                        "Halo CLI",
                        false,
                        null,
                        setup.errorCode() == null
                                ? java.util.Set.of("IDLE", "REQUIRED").contains(setup.state())
                                        ? "CLI_SETUP_REQUIRED"
                                        : "CLI_PREPARING"
                                : setup.errorCode()));
        }
        return probe("Halo CLI", tools::available);
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
