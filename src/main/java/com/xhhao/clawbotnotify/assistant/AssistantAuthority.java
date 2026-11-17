package com.xhhao.clawbotnotify.assistant;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.model.WechatBinding;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;

@Component
public class AssistantAuthority {
    private final BindingRepository bindings;
    private final CredentialStore credentials;
    private final HaloIdentityVerifier halo;
    private final RoleService roles;

    public AssistantAuthority(
            BindingRepository bindings, CredentialStore credentials, HaloIdentityVerifier halo, RoleService roles) {
        this.bindings = bindings;
        this.credentials = credentials;
        this.halo = halo;
        this.roles = roles;
    }

    public Mono<WechatBinding> check(WechatBinding expected) {
        return bindings.current()
                .filter(binding -> binding.getSpec().isEnabled()
                        && binding.getSpec().getGeneration()
                                == expected.getSpec().getGeneration()
                        && java.util.Objects.equals(
                                binding.getSpec().getOwnerWechatUserId(),
                                expected.getSpec().getOwnerWechatUserId())
                        && java.util.Objects.equals(
                                binding.getSpec().getHaloUsername(),
                                expected.getSpec().getHaloUsername()))
                .switchIfEmpty(Mono.error(new IllegalStateException("BINDING_CHANGED")))
                .flatMap(binding -> credentials
                        .read(binding.getSpec().getCredentialRef())
                        .flatMap(values -> halo.verify(
                                values.get("haloPat"), binding.getSpec().getHaloUsername()))
                        .then(roles.getRolesByUsername(binding.getSpec().getHaloUsername())
                                .any("super-role"::equals))
                        .flatMap(admin -> admin
                                ? Mono.just(binding)
                                : Mono.error(new IllegalStateException("HALO_ADMIN_REQUIRED"))));
    }
}
