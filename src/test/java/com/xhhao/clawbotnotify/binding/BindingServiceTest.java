package com.xhhao.clawbotnotify.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.channel.WechatDestinationPolicy;
import com.xhhao.clawbotnotify.channel.WechatProtocolClient;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.delivery.TestDeliveryService;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class BindingServiceTest {
    private final ExtensionFixture fixture = new ExtensionFixture();
    private final BindingRuntime runtime = mock(BindingRuntime.class);
    private final WechatProtocolClient channel = mock(WechatProtocolClient.class);
    private final HaloIdentityVerifier halo = mock(HaloIdentityVerifier.class);
    private final CredentialCipher cipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final SecretCredentialStore secrets = new SecretCredentialStore(fixture.client, cipher);
    private final BindingService service = new BindingService(
            new BindingRepository(fixture.client),
            runtime,
            channel,
            new WechatDestinationPolicy(),
            secrets,
            cipher,
            halo);
    private final ObjectMapper json = new ObjectMapper();
    private final Sinks.One<JsonNode> login = Sinks.one();

    @BeforeEach
    void setup() throws Exception {
        when(runtime.stop()).thenReturn(Mono.empty());
        when(channel.login())
                .thenReturn(Mono.just(
                        json.readTree("{\"qrcode\":\"private-qr-id\",\"qrcode_img_content\":\"qr-content\"}")));
        when(channel.loginStatus(anyString(), anyString(), nullable(String.class)))
                .thenReturn(login.asMono());
    }

    @AfterEach
    void close() {
        service.stop().block();
    }

    private void confirm() throws Exception {
        service.create("admin", "one").block();
        login.tryEmitValue(
                json.readTree(
                        "{\"status\":\"confirmed\",\"bot_token\":\"private-token\",\"ilink_bot_id\":\"bot-id\",\"ilink_user_id\":\"owner-id\"}"));
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() ->
                        assertThat(service.get().block().connectionState()).isEqualTo("AWAITING_ACTIVATION"));
    }

    @Test
    void duplicateBindingRequestReusesGenerationAndQrTransaction() {
        var first = service.create("admin", "same-key").block();
        var second = service.create("admin", "same-key").block();
        assertThat(second.generation()).isEqualTo(first.generation());
        assertThat(second.qrContent()).isEqualTo(first.qrContent());
        verify(channel, times(1)).login();
    }

    @Test
    void confirmedLoginRequiresExplicitActivationAndNeverReturnsCredential() throws Exception {
        confirm();
        var view = service.get().block();
        assertThat(view.enabled()).isFalse();
        assertThat(view.ownerIdentity()).startsWith("••••");
        assertThat(view.toString()).doesNotContain("private-token", "private-qr-id", "clawbot-credentials-");
        var binding = fixture.get(WechatBinding.class, "primary");
        assertThat(secrets.read(binding.getSpec().getCredentialRef()).block())
                .containsEntry("botToken", "private-token");
    }

    @Test
    void mismatchedHaloIdentityCannotConfigureOrActivateTheBinding() throws Exception {
        confirm();
        when(halo.verify("wrong-pat", "admin"))
                .thenReturn(Mono.error(new IllegalStateException("HALO_IDENTITY_MISMATCH")));
        assertThatThrownBy(() ->
                        service.replaceHaloCredential("admin", "wrong-pat").block())
                .hasMessage("HALO_IDENTITY_MISMATCH");
        assertThat(service.get().block().haloCredentialConfigured()).isFalse();
        assertThatThrownBy(() -> service.activate("admin").block()).hasMessageContaining("HALO_CREDENTIAL_REQUIRED");
        var binding = fixture.get(WechatBinding.class, "primary");
        assertThat(secrets.read(binding.getSpec().getCredentialRef()).block()).doesNotContainKey("haloPat");
    }

    @Test
    void anotherHaloAdminCannotReplaceTheBindingOwnersCredential() throws Exception {
        confirm();
        assertThatThrownBy(() ->
                        service.replaceHaloCredential("another-admin", "token").block())
                .hasMessageContaining("BINDING_OWNER_REQUIRED");
    }

    @Test
    void activationRechecksIdentityAndTestMessagesAreIdempotent() throws Exception {
        confirm();
        when(halo.verify("valid-pat", "admin")).thenReturn(Mono.empty());
        service.replaceHaloCredential("admin", "valid-pat").block();
        var active = service.activate("admin").block();
        assertThat(active.connectionState()).isEqualTo("AWAITING_MESSAGE");
        assertThat(active.enabled()).isTrue();
        verify(halo, times(2)).verify("valid-pat", "admin");
        verify(runtime).start();
        var deliveries = new TestDeliveryService(fixture.client, service);
        var first = deliveries.enqueue("admin", "same-test").block();
        var second = deliveries.enqueue("admin", "same-test").block();
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(deliveries.list(1, 20).block().total()).isEqualTo(1);
    }

    @Test
    void disconnectDeletesCredentialsAndKeepsTheBindingDisabled() throws Exception {
        confirm();
        String reference = fixture.get(WechatBinding.class, "primary").getSpec().getCredentialRef();
        service.disconnect("admin").block();
        assertThat(service.get().block().connectionState()).isEqualTo("DISABLED");
        assertThat(service.get().block().enabled()).isFalse();
        assertThat(fixture.get(run.halo.app.extension.Secret.class, reference)).isNull();
    }
}
