package com.xhhao.clawbotnotify.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.delivery.TestDeliveryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

class ClawBotEndpointTest {
    private final BindingService bindings = mock(BindingService.class);
    private final TestDeliveryService deliveries = mock(TestDeliveryService.class);

    private WebTestClient client(String authority) {
        var handler = RouterFunctions.toWebHandler(new ClawBotEndpoint(bindings, deliveries).endpoint());
        return WebTestClient.bindToWebHandler(handler)
                .webFilter((exchange, chain) -> {
                    if (authority == null) return chain.filter(exchange);
                    var auth = new UsernamePasswordAuthenticationToken(
                            "admin", "hidden", List.of(new SimpleGrantedAuthority(authority)));
                    return chain.filter(
                            exchange.mutate().principal(Mono.just(auth)).build());
                })
                .build();
    }

    @Test
    void anonymousRequestsNeverEnterTheBindingService() {
        client(null).get().uri("/bindings/primary").exchange().expectStatus().isForbidden();
        verifyNoInteractions(bindings, deliveries);
    }

    @Test
    void ordinaryAuthenticatedUsersCannotAccessManagementOrHistory() {
        var client = client("ROLE_authenticated");
        client.post()
                .uri("/bindings")
                .header("Idempotency-Key", "one")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.get().uri("/deliveries").exchange().expectStatus().isForbidden();
        verifyNoInteractions(bindings, deliveries);
    }

    @Test
    void invalidPaginationReturnsABoundedClientError() {
        client("ROLE_super-role")
                .get()
                .uri("/deliveries?page=1&size=101")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("INVALID_PAGINATION");
        verifyNoInteractions(deliveries);
    }

    @Test
    void upstreamExceptionMessagesAreNotReturnedToTheBrowser() {
        when(bindings.get()).thenReturn(Mono.error(new IllegalStateException("secret-token-in-an-unsafe-error")));
        client("ROLE_super-role")
                .get()
                .uri("/bindings/primary")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("CONNECTION_OR_STORAGE_FAILED");
    }

    @Test
    void emptyCredentialBodyIsRejectedBeforeTheServiceSeesIt() {
        client("ROLE_super-role")
                .put()
                .uri("/bindings/primary/halo-credential")
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus()
                .isBadRequest();
        verifyNoInteractions(bindings);
    }
}
