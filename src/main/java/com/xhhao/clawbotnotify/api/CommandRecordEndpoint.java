package com.xhhao.clawbotnotify.api;

import static com.xhhao.clawbotnotify.api.ConsoleEndpointSupport.*;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.webflux.core.fn.SpringdocRouteBuilder.route;

import com.xhhao.clawbotnotify.command.CommandRecordService;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class CommandRecordEndpoint implements CustomEndpoint {
    private final CommandRecordService records;

    public CommandRecordEndpoint(CommandRecordService records) {
        this.records = records;
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.clawbot.xhhao.com", "v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route().GET(
                        "/commands",
                        request -> ok(records.list(
                                number(request, "page", 1, Integer.MAX_VALUE),
                                number(request, "size", 20, 100),
                                request.queryParam("type").orElse(null),
                                request.queryParam("result").orElse(null))),
                        b -> {
                            operation(b, "ListClawBotCommands", CommandView.Page.class);
                            for (String name : new String[] {"page", "size"})
                                b.parameter(parameterBuilder()
                                        .in(ParameterIn.QUERY)
                                        .name(name)
                                        .implementation(Integer.class));
                            for (String name : new String[] {"type", "result"})
                                b.parameter(parameterBuilder()
                                        .in(ParameterIn.QUERY)
                                        .name(name)
                                        .implementation(String.class));
                        })
                .GET(
                        "/commands/{name}",
                        request -> admin(request)
                                .flatMap(username -> ok(records.detail(username, request.pathVariable("name")))),
                        b -> {
                            operation(b, "GetClawBotCommand", CommandDetail.class);
                            b.parameter(parameterBuilder()
                                    .in(ParameterIn.PATH)
                                    .name("name")
                                    .required(true)
                                    .implementation(String.class));
                        })
                .build()
                .filter((request, next) -> admin(request)
                        .flatMap(ignored -> next.handle(request))
                        .onErrorResume(ConsoleEndpointSupport::problem));
    }
}
