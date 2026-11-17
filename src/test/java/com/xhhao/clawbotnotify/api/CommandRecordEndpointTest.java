package com.xhhao.clawbotnotify.api;

import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.command.CommandRecordService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Mono;

class CommandRecordEndpointTest {
    final CommandRecordService records = mock(CommandRecordService.class);

    WebTestClient client(String authority) {
        return WebTestClient.bindToWebHandler(
                        RouterFunctions.toWebHandler(new CommandRecordEndpoint(records).endpoint()))
                .webFilter((exchange, chain) -> authority == null
                        ? chain.filter(exchange)
                        : chain.filter(exchange.mutate()
                                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                                        "admin", "hidden", List.of(new SimpleGrantedAuthority(authority)))))
                                .build()))
                .build();
    }

    @Test
    void anonymousAndOrdinaryAccountsCannotListOrLoadContent() {
        for (String authority : new String[] {null, "ROLE_authenticated"}) {
            var client = client(authority);
            client.get().uri("/commands").exchange().expectStatus().isForbidden();
            client.get().uri("/commands/in-test").exchange().expectStatus().isForbidden();
        }
        verifyNoInteractions(records);
    }

    @Test
    void invalidPageSizeNeverReachesRecordService() {
        client("ROLE_super-role")
                .get()
                .uri("/commands?size=101")
                .exchange()
                .expectStatus()
                .isBadRequest();
        verifyNoInteractions(records);
    }

    @Test
    void detailUsesAuthenticatedUsernameAndSanitizesUnexpectedErrors() {
        when(records.detail("admin", "in-test"))
                .thenReturn(Mono.error(new IllegalStateException("private-token-in-error")));
        client("ROLE_super-role")
                .get()
                .uri("/commands/in-test")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("CONNECTION_OR_STORAGE_FAILED");
    }
}
