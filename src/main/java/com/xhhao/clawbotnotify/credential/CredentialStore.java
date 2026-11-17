package com.xhhao.clawbotnotify.credential;

import java.util.Map;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

public interface CredentialStore {
    Mono<Void> create(String reference, Map<String, String> values);

    Mono<Map<String, String>> read(String reference);

    Mono<Void> update(String reference, Consumer<Map<String, String>> change);

    Mono<Void> delete(String reference);
}
