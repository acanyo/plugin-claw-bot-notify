package com.xhhao.clawbotnotify.api;

import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

class CliSetupEndpointTest {
    final ManagedCliRuntime runtime = mock(ManagedCliRuntime.class);

    WebTestClient client(String authority) {
        return WebTestClient.bindToWebHandler(RouterFunctions.toWebHandler(new CliSetupEndpoint(runtime).endpoint()))
                .webFilter((exchange, chain) -> authority == null
                        ? chain.filter(exchange)
                        : chain.filter(exchange.mutate()
                                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                                        "admin", "hidden", List.of(new SimpleGrantedAuthority(authority)))))
                                .build()))
                .build();
    }

    @Test
    void unauthorizedUsersCannotInspectOrInstallServerTools() {
        for (String authority : new String[] {null, "ROLE_authenticated"}) {
            client(authority)
                    .get()
                    .uri("/assistant/setup")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            for (String action : new String[] {"detect", "install"})
                client(authority)
                        .post()
                        .uri("/assistant/setup/" + action)
                        .exchange()
                        .expectStatus()
                        .isForbidden();
        }
        verifyNoInteractions(runtime);
    }

    @Test
    void readingStatusOnlyStartsInspectionAndInstallationRequiresItsOwnPost() {
        when(runtime.status())
                .thenReturn(new ManagedCliRuntime.Status("MANAGED", "REQUIRED", "1.4.0", null, null, null, null));
        var client = client("ROLE_super-role");
        client.get()
                .uri("/assistant/setup")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("REQUIRED");
        verify(runtime).start();
        verify(runtime, never()).install();
        client.post().uri("/assistant/setup/detect").exchange().expectStatus().isOk();
        verify(runtime).detect();
        verify(runtime, never()).install();
        client.post().uri("/assistant/setup/install").exchange().expectStatus().isOk();
        verify(runtime).install();
    }
}
