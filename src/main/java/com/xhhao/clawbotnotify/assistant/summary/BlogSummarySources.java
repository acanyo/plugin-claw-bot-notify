package com.xhhao.clawbotnotify.assistant.summary;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/** Selects published sources deterministically, including every page and both time boundaries. */
public final class BlogSummarySources {
    public record Article(String name, String title, Instant publishedAt, String content) {}

    public record Snapshot(long total, List<Article> articles) {}

    public Mono<List<Article>> read(BlogSummarySchedule.Window window, Function<List<String>, Mono<JsonNode>> run) {
        return snapshot(window, run).map(Snapshot::articles);
    }

    public Mono<Snapshot> snapshot(BlogSummarySchedule.Window window, Function<List<String>, Mono<JsonNode>> run) {
        return ReportPages.read(List.of("post", "list"), run)
                .map(item -> item.has("post") ? item.path("post") : item)
                .filter(BlogSummaryPreview::eligible)
                .distinct(post -> post.path("metadata").path("name").asText())
                .collectList()
                .flatMap(posts -> reactor.core.publisher.Flux.fromIterable(posts)
                        .filter(post -> inside(post, window))
                        .map(post -> post.path("metadata").path("name").asText())
                        .distinct()
                        .flatMapSequential(
                                name -> {
                                    if (!name.matches("[a-z0-9][a-z0-9.-]{0,252}")) return Mono.error(invalid());
                                    return run.apply(List.of("post", "get", name))
                                            .map(output -> {
                                                var post = output.has("post") ? output.path("post") : output;
                                                if (!name.equals(post.path("metadata")
                                                                .path("name")
                                                                .asText())
                                                        || !BlogSummaryPreview.eligible(post)
                                                        || !inside(post, window)
                                                        || !output.path("content")
                                                                .path("raw")
                                                                .isTextual()) throw invalid();
                                                return new Article(
                                                        name,
                                                        post.path("spec")
                                                                .path("title")
                                                                .asText(),
                                                        publication(post),
                                                        output.path("content")
                                                                .path("raw")
                                                                .asText());
                                            });
                                },
                                4,
                                1)
                        .sort(Comparator.comparing(Article::publishedAt).thenComparing(Article::name))
                        .collectList()
                        .map(articles -> new Snapshot(posts.size(), articles)));
    }

    private static boolean inside(JsonNode post, BlogSummarySchedule.Window window) {
        Instant time = publication(post);
        return !time.isBefore(window.start()) && time.isBefore(window.end());
    }

    private static Instant publication(JsonNode post) {
        try {
            return Instant.parse(post.path("spec").path("publishTime").asText());
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("AI_SUMMARY_SOURCE_INVALID");
    }
}
