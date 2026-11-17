package com.xhhao.clawbotnotify.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

@Component
public class SecretCredentialStore implements CredentialStore {
    private final ReactiveExtensionClient client;
    private final CredentialCipher cipher;
    private final ObjectMapper json = new ObjectMapper();

    public SecretCredentialStore(ReactiveExtensionClient client, CredentialCipher cipher) {
        this.client = client;
        this.cipher = cipher;
    }

    @Override
    public Mono<Void> create(String reference, Map<String, String> values) {
        return Mono.defer(() -> {
            var secret = new Secret();
            var metadata = new Metadata();
            metadata.setName(reference);
            secret.setMetadata(metadata);
            secret.setType(Secret.SECRET_TYPE_OPAQUE);
            secret.setData(Map.of("encrypted-v1", encode(reference, values)));
            return client.create(secret).then();
        });
    }

    @Override
    public Mono<Map<String, String>> read(String reference) {
        return client.get(Secret.class, reference).map(secret -> decode(reference, secret));
    }

    @Override
    public Mono<Void> update(String reference, Consumer<Map<String, String>> change) {
        return Mono.defer(() -> client.get(Secret.class, reference).flatMap(secret -> {
                    var values = decode(reference, secret);
                    change.accept(values);
                    secret.setData(Map.of("encrypted-v1", encode(reference, values)));
                    return client.update(secret);
                }))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(50))
                        .filter(OptimisticLockingFailureException.class::isInstance))
                .then();
    }

    @Override
    public Mono<Void> delete(String reference) {
        return client.fetch(Secret.class, reference).flatMap(client::delete).then();
    }

    private byte[] encode(String reference, Map<String, String> values) {
        try {
            return cipher.encrypt(reference, json.writeValueAsBytes(values));
        } catch (Exception e) {
            throw new IllegalStateException("CREDENTIAL_WRITE_FAILED");
        }
    }

    private Map<String, String> decode(String reference, Secret secret) {
        try {
            return new HashMap<>(json.readValue(
                    cipher.decrypt(reference, secret.getData().get("encrypted-v1")),
                    new TypeReference<Map<String, String>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("CREDENTIAL_READ_FAILED");
        }
    }
}
