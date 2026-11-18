package com.xhhao.clawbotnotify.api;

import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.assistant.runtime.AssistantRuntimeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

class AssistantRuntimeEndpointTest {
    final AssistantRuntimeService runtime = mock(AssistantRuntimeService.class);

    WebTestClient client(String authority) {
        return WebTestClient.bindToWebHandler(
                        RouterFunctions.toWebHandler(new AssistantRuntimeEndpoint(runtime).endpoint()))
                .webFilter((exchange, chain) -> authority == null
                        ? chain.filter(exchange)
                        : chain.filter(exchange.mutate()
                                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                                        "admin", "hidden", List.of(new SimpleGrantedAuthority(authority)))))
                                .build()))
                .build();
    }

    @Test
    void unauthorizedStatusReadsCannotLaunchDependencyChecks() {
        for (String authority : new String[] {null, "ROLE_authenticated"}) {
            client(authority)
                    .get()
                    .uri("/assistant/runtime")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            client(authority)
                    .post()
                    .uri("/assistant/runtime/cli/retry")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        verifyNoInteractions(runtime);
    }

    @Test
    void adminReceivesSafeDependencyErrors() {
        when(runtime.status()).thenReturn(Mono.error(new IllegalStateException("private-token")));
        client("ROLE_super-role")
                .get()
                .uri("/assistant/runtime")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("CONNECTION_OR_STORAGE_FAILED");
    }
}
