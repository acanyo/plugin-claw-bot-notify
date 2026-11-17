package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.plugin.ReactiveSettingFetcher;

class AssistantAuthorityTest {
    final BindingRepository bindings = mock(BindingRepository.class);
    final CredentialStore secrets = mock(CredentialStore.class);
    final HaloIdentityVerifier halo = mock(HaloIdentityVerifier.class);
    final RoleService roles = mock(RoleService.class);
    final AssistantAuthority authority = new AssistantAuthority(bindings, secrets, halo, roles);

    WechatBinding binding() {
        var binding = new WechatBinding();
        var spec = new WechatBinding.Spec();
        spec.setGeneration(3);
        spec.setHaloUsername("admin");
        spec.setOwnerWechatUserId("owner");
        spec.setCredentialRef("binding-secret");
        spec.setEnabled(true);
        binding.setSpec(spec);
        return binding;
    }

    @Test
    void verifiesCurrentPatIdentityAndAdminRoleEveryTime() {
        var binding = binding();
        when(bindings.current()).thenReturn(Mono.just(binding));
        when(secrets.read("binding-secret")).thenReturn(Mono.just(Map.of("haloPat", "secret-pat")));
        when(halo.verify("secret-pat", "admin")).thenReturn(Mono.empty());
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("super-role"));
        authority.check(binding).block();
        when(roles.getRolesByUsername("admin")).thenReturn(Flux.just("role-template-view-comments"));
        assertThatThrownBy(() -> authority.check(binding).block()).hasMessage("HALO_ADMIN_REQUIRED");
        verify(halo, times(2)).verify("secret-pat", "admin");
    }

    @Test
    void rejectsChangedOwnerOrGenerationBeforeReadingCredentials() {
        var expected = binding();
        var current = binding();
        current.getSpec().setOwnerWechatUserId("other-owner");
        when(bindings.current()).thenReturn(Mono.just(current));
        assertThatThrownBy(() -> authority.check(expected).block()).hasMessage("BINDING_CHANGED");
        current.getSpec().setOwnerWechatUserId("owner");
        current.getSpec().setGeneration(4);
        assertThatThrownBy(() -> authority.check(expected).block()).hasMessage("BINDING_CHANGED");
        verifyNoInteractions(secrets, halo, roles);
    }

    @Test
    void missingAndMalformedSettingsKeepTheAssistantClosed() {
        var fetcher = mock(ReactiveSettingFetcher.class);
        var settings = new AssistantSettings(fetcher);
        when(fetcher.fetch("assistant", AssistantSettings.Config.class)).thenReturn(Mono.empty());
        assertThat(settings.get().block().isEnabled()).isFalse();
        var config = new AssistantSettings.Config();
        config.setEnabled(true);
        config.setMaxContextChars(0);
        when(fetcher.fetch("assistant", AssistantSettings.Config.class)).thenReturn(Mono.just(config));
        assertThatThrownBy(() -> settings.get().block()).hasMessage("AI_SETTINGS_INVALID");
        when(fetcher.fetch("assistant", AssistantSettings.Config.class))
                .thenReturn(Mono.error(new IllegalArgumentException("private-config")));
        assertThatThrownBy(() -> settings.get().block()).hasMessage("AI_SETTINGS_INVALID");
    }
}
