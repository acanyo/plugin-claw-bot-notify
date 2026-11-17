package com.xhhao.clawbotnotify.command;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class InstanceIdentity {
    private static final String NAME = "clawbot-instance-identity";
    private final ReactiveExtensionClient resources;

    public InstanceIdentity(ReactiveExtensionClient resources) {
        this.resources = resources;
    }

    public Mono<String> get() {
        return resources
                .fetch(ConfigMap.class, NAME)
                .switchIfEmpty(Mono.defer(() -> {
                    var config = new ConfigMap();
                    var metadata = new Metadata();
                    metadata.setName(NAME);
                    config.setMetadata(metadata);
                    config.setData(Map.of("siteInstanceId", UUID.randomUUID().toString()));
                    return resources
                            .create(config)
                            .onErrorResume(
                                    e -> resources.fetch(ConfigMap.class, NAME).switchIfEmpty(Mono.error(e)));
                }))
                .map(config -> {
                    String id = config.getData().get("siteInstanceId");
                    if (id == null || id.isBlank()) throw new IllegalStateException("INSTANCE_ID_INVALID");
                    return id;
                });
    }
}
