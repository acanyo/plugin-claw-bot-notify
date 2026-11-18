package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;

@Component
public class ManagedCliCredentials {
    public static final String BINDING_CONTEXT = ManagedCliCredentials.class.getName() + ".binding";
    private final BindingRepository bindings;
    private final CredentialStore secrets;
    private final ExternalUrlSupplier externalUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ManagedCliCredentials(BindingRepository bindings, CredentialStore secrets, ExternalUrlSupplier externalUrl) {
        this.bindings = bindings;
        this.secrets = secrets;
        this.externalUrl = externalUrl;
    }

    public Mono<String> header(String profile) {
        return Mono.deferContextual(context -> bindings.current()
                .filter(binding -> binding.getSpec().isEnabled())
                .switchIfEmpty(Mono.error(new IllegalStateException("CLI_BINDING_REQUIRED")))
                .filter(binding -> {
                    if (!context.hasKey(BINDING_CONTEXT)) return true;
                    var expected = (com.xhhao.clawbotnotify.model.WechatBinding) context.get(BINDING_CONTEXT);
                    return binding.getSpec().getGeneration()
                                    == expected.getSpec().getGeneration()
                            && java.util.Objects.equals(
                                    binding.getSpec().getHaloUsername(),
                                    expected.getSpec().getHaloUsername())
                            && java.util.Objects.equals(
                                    binding.getSpec().getOwnerWechatUserId(),
                                    expected.getSpec().getOwnerWechatUserId());
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("BINDING_CHANGED")))
                .flatMap(binding -> secrets.read(binding.getSpec().getCredentialRef()))
                .map(values -> {
                    String token = values.get("haloPat");
                    if (token == null || token.isBlank()) throw new IllegalStateException("CLI_BINDING_REQUIRED");
                    try {
                        var url = externalUrl.getRaw().toURI();
                        if (!java.util.Set.of("http", "https").contains(url.getScheme())
                                || url.getHost() == null
                                || url.getUserInfo() != null
                                || url.getFragment() != null
                                || url.getQuery() != null) throw new IllegalArgumentException();
                        return json.writeValueAsString(
                                Map.of("profile", profile, "baseUrl", url.toString(), "token", token));
                    } catch (Exception error) {
                        throw new IllegalStateException("CLI_SITE_MISMATCH");
                    }
                }));
    }
}
