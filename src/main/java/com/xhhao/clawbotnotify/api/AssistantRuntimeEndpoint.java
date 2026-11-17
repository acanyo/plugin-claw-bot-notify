package com.xhhao.clawbotnotify.api;

import static com.xhhao.clawbotnotify.api.ConsoleEndpointSupport.*;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import com.xhhao.clawbotnotify.assistant.runtime.AssistantRuntimeService;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class AssistantRuntimeEndpoint implements CustomEndpoint {
    private final AssistantRuntimeService runtime;

    public AssistantRuntimeEndpoint(AssistantRuntimeService runtime) {
        this.runtime = runtime;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.clawbot.xhhao.com", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().GET(
                        "/assistant/runtime",
                        request -> ok(runtime.status()),
                        b -> operation(b, "GetClawBotAssistantRuntime", AssistantRuntimeView.class))
                .build()
                .filter((request, next) -> admin(request)
                        .flatMap(ignored -> next.handle(request))
                        .onErrorResume(ConsoleEndpointSupport::problem));
    }
}
