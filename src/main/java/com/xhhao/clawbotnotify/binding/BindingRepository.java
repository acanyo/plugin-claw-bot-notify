package com.xhhao.clawbotnotify.binding;

import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ReactiveExtensionClient;

@Component
public class BindingRepository {
    public static final String NAME = "primary";
    private final ReactiveExtensionClient client;

    public BindingRepository(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<WechatBinding> current() {
        return client.fetch(WechatBinding.class, NAME);
    }

    public Mono<WechatBinding> require() {
        return current()
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "BINDING_REQUIRED")));
    }

    public Mono<WechatBinding> change(long generation, Consumer<WechatBinding> action) {
        return Mono.defer(() -> require().flatMap(binding -> {
                    if (binding.getSpec().getGeneration() != generation) {
                        return Mono.error(
                                new ResponseStatusException(HttpStatus.CONFLICT, "BINDING_GENERATION_CHANGED"));
                    }
                    action.accept(binding);
                    return client.update(binding);
                }))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
                        .filter(OptimisticLockingFailureException.class::isInstance));
    }

    public Mono<WechatBinding> save(WechatBinding binding) {
        return binding.getMetadata().getVersion() == null ? client.create(binding) : client.update(binding);
    }
}
