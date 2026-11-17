package com.xhhao.clawbotnotify.delivery;

import com.xhhao.clawbotnotify.api.DeliveryView;
import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.model.OutboundTask;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class TestDeliveryService {
    private final ReactiveExtensionClient resources;
    private final BindingService bindings;

    public TestDeliveryService(ReactiveExtensionClient resources, BindingService bindings) {
        this.resources = resources;
        this.bindings = bindings;
    }

    public Mono<DeliveryView> enqueue(String username, String key) {
        return bindings.owned(username).flatMap(binding -> {
            if (!binding.getSpec().isEnabled())
                return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "BINDING_DISABLED"));
            String eventKey =
                    BindingService.requestHash("test:" + binding.getSpec().getGeneration(), key);
            String name = "test-" + eventKey;
            return resources
                    .fetch(OutboundTask.class, name)
                    .switchIfEmpty(Mono.defer(() -> {
                        var task = new OutboundTask();
                        var metadata = new Metadata();
                        metadata.setName(name);
                        task.setMetadata(metadata);
                        var spec = new OutboundTask.Spec();
                        spec.setGeneration(binding.getSpec().getGeneration());
                        spec.setKind("TEST");
                        spec.setEventKey(eventKey);
                        spec.setClientId("halo-clawbot-" + UUID.randomUUID());
                        spec.setTargetUserId(binding.getSpec().getOwnerWechatUserId());
                        task.setSpec(spec);
                        return resources
                                .create(task)
                                .onErrorResume(e -> resources
                                        .fetch(OutboundTask.class, name)
                                        .switchIfEmpty(Mono.error(e)));
                    }))
                    .map(TestDeliveryService::view);
        });
    }

    public Mono<DeliveryView.Page> list(int page, int size) {
        return resources
                .listBy(
                        OutboundTask.class,
                        ListOptions.builder()
                                .fieldQuery(run.halo.app.extension.index.query.Queries.isNull("spec.cleanedAt"))
                                .build(),
                        PageRequestImpl.of(page, size, Sort.by(Sort.Direction.DESC, "metadata.creationTimestamp")))
                .map(result -> new DeliveryView.Page(
                        page,
                        size,
                        result.getTotal(),
                        result.getItems().stream()
                                .map(TestDeliveryService::view)
                                .toList()));
    }

    private static DeliveryView view(OutboundTask task) {
        var spec = task.getSpec();
        return new DeliveryView(
                task.getMetadata().getName(),
                spec.getGeneration(),
                spec.getState().name(),
                spec.getKind() == null ? "TEST" : spec.getKind(),
                spec.getShortCode(),
                spec.getAttemptedAt(),
                spec.getFinishedAt(),
                spec.getErrorCode(),
                spec.getUpstreamMessageId());
    }
}
