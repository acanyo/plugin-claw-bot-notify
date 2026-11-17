package com.xhhao.clawbotnotify.assistant.summary;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A complete validated pagination stream; CLI totals may include private or draft resources. */
final class ReportPages {
    private ReportPages() {}

    static Flux<JsonNode> read(List<String> path, Function<List<String>, Mono<JsonNode>> run) {
        return page(path, 1, run)
                .expand(
                        page -> page.path("hasNext").asBoolean()
                                ? page(path, page.path("page").asInt() + 1, run)
                                : Mono.empty(),
                        1)
                .concatMapIterable(page -> page.path("items"));
    }

    private static Mono<JsonNode> page(List<String> path, int number, Function<List<String>, Mono<JsonNode>> run) {
        var args = new ArrayList<>(path);
        args.add("--page=" + number);
        args.add("--size=100");
        return Mono.defer(() -> run.apply(args)).map(page -> {
            if (page.path("page").asInt() != number
                    || !page.path("items").isArray()
                    || !page.path("hasNext").isBoolean()
                    || page.path("hasNext").asBoolean() && page.path("items").isEmpty())
                throw new IllegalStateException("AI_SUMMARY_SOURCE_INVALID");
            return page;
        });
    }
}
