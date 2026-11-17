package com.xhhao.clawbotnotify.delivery;

import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.StableIds;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class OutboundQueue {
    private final ReactiveExtensionClient resources;

    public OutboundQueue(ReactiveExtensionClient resources) {
        this.resources = resources;
    }

    public Mono<OutboundTask> enqueue(
            WechatBinding binding, String kind, String source, Consumer<OutboundTask.Spec> reference) {
        String key = StableIds.hash("primary", Long.toString(binding.getSpec().getGeneration()), kind, source);
        String name = "out-" + key;
        return resources.fetch(OutboundTask.class, name).switchIfEmpty(Mono.defer(() -> {
            var task = new OutboundTask();
            var metadata = new Metadata();
            metadata.setName(name);
            task.setMetadata(metadata);
            var spec = new OutboundTask.Spec();
            spec.setGeneration(binding.getSpec().getGeneration());
            spec.setTargetUserId(binding.getSpec().getOwnerWechatUserId());
            spec.setKind(kind);
            spec.setEventKey(key);
            spec.setClientId("halo-clawbot-" + key);
            reference.accept(spec);
            task.setSpec(spec);
            return resources
                    .create(task)
                    .onErrorResume(
                            e -> resources.fetch(OutboundTask.class, name).switchIfEmpty(Mono.error(e)));
        }));
    }
}
