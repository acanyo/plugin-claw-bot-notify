package com.xhhao.clawbotnotify.api;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

import com.xhhao.clawbotnotify.channel.ChannelException;
import java.util.UUID;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

final class ConsoleEndpointSupport {
    private static final String TAG = "ClawBotV1alpha1Console";

    private ConsoleEndpointSupport() {}

    static Mono<String> admin(ServerRequest request) {
        return request.principal()
                .filter(p -> p instanceof Authentication a
                        && a.isAuthenticated()
                        && a.getAuthorities().stream().anyMatch(auth -> "ROLE_super-role".equals(auth.getAuthority())))
                .map(java.security.Principal::getName)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED")));
    }

    static int number(ServerRequest r, String name, int defaultValue, int max) {
        try {
            int value = Integer.parseInt(r.queryParam(name).orElse(Integer.toString(defaultValue)));
            if (value < 1 || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION");
        }
    }

    static <T> Mono<T> body(ServerRequest r, Class<T> type) {
        return r.bodyToMono(type)
                .switchIfEmpty(
                        Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "REQUEST_BODY_REQUIRED")));
    }

    static Mono<ServerResponse> ok(Mono<?> value) {
        return value.flatMap(v -> ServerResponse.ok().bodyValue(v));
    }

    static Mono<ServerResponse> problem(Throwable error) {
        var status = HttpStatus.SERVICE_UNAVAILABLE;
        String code = "CONNECTION_OR_STORAGE_FAILED";
        if (error instanceof ResponseStatusException e) {
            status = HttpStatus.valueOf(e.getStatusCode().value());
            if (e.getReason() != null && e.getReason().matches("[A-Z_]{1,80}")) code = e.getReason();
        } else if (error instanceof ChannelException e) {
            status = HttpStatus.BAD_GATEWAY;
            code = "WECHAT_" + e.code().name();
        } else if (error instanceof IllegalStateException e
                && e.getMessage() != null
                && e.getMessage().matches("[A-Z_]{1,80}")) {
            code = e.getMessage();
        } else if (error instanceof org.springframework.web.server.ServerWebInputException) {
            status = HttpStatus.BAD_REQUEST;
            code = "INVALID_REQUEST_BODY";
        }
        var detail = ProblemDetail.forStatusAndDetail(status, code);
        detail.setTitle("ClawBot request failed");
        detail.setProperty("code", code);
        detail.setProperty("traceId", UUID.randomUUID().toString());
        detail.setProperty("retryable", false);
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(detail);
    }

    static void operation(Builder b, String id, Class<?> response) {
        b.operationId(id)
                .tag(TAG)
                .response(responseBuilder().responseCode("200").implementation(response));
    }
}
