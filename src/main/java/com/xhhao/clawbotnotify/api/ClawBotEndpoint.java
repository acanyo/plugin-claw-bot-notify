package com.xhhao.clawbotnotify.api;

import static com.xhhao.clawbotnotify.api.ConsoleEndpointSupport.*;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.delivery.TestDeliveryService;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springdoc.core.fn.builders.operation.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class ClawBotEndpoint implements CustomEndpoint {
    private final BindingService bindings;
    private final TestDeliveryService deliveries;

    public ClawBotEndpoint(BindingService bindings, TestDeliveryService deliveries) {
        this.bindings = bindings;
        this.deliveries = deliveries;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.clawbot.xhhao.com", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().GET(
                        "/bindings/{id}",
                        r -> id(r).then(ok(bindings.get())),
                        b -> binding(b, "GetClawBotBinding", true))
                .POST("/bindings", r -> ok(bindings.create(username(r), key(r))), b -> {
                    binding(b, "CreateClawBotBinding", false);
                    idempotency(b);
                })
                .POST(
                        "/bindings/{id}/verify-code",
                        r -> id(r).then(body(r, VerifyCodeRequest.class))
                                .flatMap(p -> ok(bindings.verifyCode(username(r), p.code()))),
                        b -> {
                            binding(b, "VerifyClawBotCode", true);
                            b.requestBody(requestBodyBuilder().required(true).implementation(VerifyCodeRequest.class));
                        })
                .PUT(
                        "/bindings/{id}/halo-credential",
                        r -> id(r).then(body(r, HaloCredentialRequest.class))
                                .flatMap(p -> ok(bindings.replaceHaloCredential(username(r), p.token()))),
                        b -> {
                            binding(b, "ReplaceClawBotHaloCredential", true);
                            b.requestBody(
                                    requestBodyBuilder().required(true).implementation(HaloCredentialRequest.class));
                        })
                .POST(
                        "/bindings/{id}/activate",
                        r -> id(r).then(ok(bindings.activate(username(r)))),
                        b -> binding(b, "ActivateClawBotBinding", true))
                .DELETE(
                        "/bindings/{id}",
                        r -> id(r).then(ok(bindings.disconnect(username(r)))),
                        b -> binding(b, "DisconnectClawBotBinding", true))
                .POST(
                        "/bindings/{id}/test-messages",
                        r -> id(r).then(ok(deliveries.enqueue(username(r), key(r)))),
                        b -> {
                            operation(b, "CreateClawBotTestMessage", DeliveryView.class);
                            pathId(b);
                            idempotency(b);
                        })
                .GET(
                        "/deliveries",
                        r -> ok(deliveries.list(number(r, "page", 1, Integer.MAX_VALUE), number(r, "size", 20, 100))),
                        b -> {
                            operation(b, "ListClawBotDeliveries", DeliveryView.Page.class);
                            b.parameter(parameterBuilder()
                                    .in(ParameterIn.QUERY)
                                    .name("page")
                                    .implementation(Integer.class));
                            b.parameter(parameterBuilder()
                                    .in(ParameterIn.QUERY)
                                    .name("size")
                                    .implementation(Integer.class));
                        })
                .build()
                .filter((request, next) -> admin(request)
                        .flatMap(name -> {
                            request.attributes().put("clawbot.username", name);
                            return next.handle(request);
                        })
                        .onErrorResume(ConsoleEndpointSupport::problem));
    }

    private static String username(ServerRequest r) {
        return (String) r.attributes().get("clawbot.username");
    }

    private static String key(ServerRequest r) {
        return r.headers().firstHeader("Idempotency-Key");
    }

    private static Mono<Void> id(ServerRequest r) {
        return "primary".equals(r.pathVariable("id"))
                ? Mono.empty()
                : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "BINDING_NOT_FOUND"));
    }

    private static void binding(Builder b, String id, boolean path) {
        operation(b, id, BindingView.class);
        if (path) pathId(b);
    }

    private static void pathId(Builder b) {
        b.parameter(parameterBuilder()
                .in(ParameterIn.PATH)
                .name("id")
                .required(true)
                .implementation(String.class));
    }

    private static void idempotency(Builder b) {
        b.parameter(parameterBuilder()
                .in(ParameterIn.HEADER)
                .name("Idempotency-Key")
                .required(true)
                .implementation(String.class));
    }

    public record VerifyCodeRequest(String code) {
        @Override
        public String toString() {
            return "VerifyCodeRequest[REDACTED]";
        }
    }

    public record HaloCredentialRequest(String token) {
        @Override
        public String toString() {
            return "HaloCredentialRequest[REDACTED]";
        }
    }
}
