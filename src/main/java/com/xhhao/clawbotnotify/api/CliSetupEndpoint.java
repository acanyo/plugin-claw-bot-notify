package com.xhhao.clawbotnotify.api;

import static com.xhhao.clawbotnotify.api.ConsoleEndpointSupport.*;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class CliSetupEndpoint implements CustomEndpoint {
    private final ManagedCliRuntime runtime;

    public CliSetupEndpoint(ManagedCliRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.clawbot.xhhao.com", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().GET(
                        "/assistant/setup",
                        request -> {
                            runtime.start();
                            return ok(reactor.core.publisher.Mono.fromSupplier(runtime::status));
                        },
                        b -> operation(b, "GetClawBotSetup", ManagedCliRuntime.Status.class))
                .POST(
                        "/assistant/setup/detect",
                        request -> {
                            runtime.detect();
                            return ok(reactor.core.publisher.Mono.fromSupplier(runtime::status));
                        },
                        b -> operation(b, "DetectClawBotEnvironment", ManagedCliRuntime.Status.class))
                .POST(
                        "/assistant/setup/install",
                        request -> {
                            runtime.install();
                            return ok(reactor.core.publisher.Mono.fromSupplier(runtime::status));
                        },
                        b -> operation(b, "InstallClawBotTools", ManagedCliRuntime.Status.class))
                .build()
                .filter((request, next) -> admin(request)
                        .flatMap(ignored -> next.handle(request))
                        .onErrorResume(ConsoleEndpointSupport::problem));
    }
}
