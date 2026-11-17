package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in read-only verification against an explicitly selected installed executable. */
@EnabledIfEnvironmentVariable(named = "CLAWBOT_NATIVE_CLI", matches = ".+")
class HaloCliNativeTest {
    @Test
    void everyInstalledCommandIsDiscoverableAndRealCommentQueriesPreserveStatistics() {
        var cli = new HaloCliProcess(System.getenv("CLAWBOT_NATIVE_CLI"), "clawbot", Duration.ofSeconds(20));
        var catalog = new HaloCliCatalog(cli);
        var leaves = new ArrayList<List<String>>();
        visit(catalog, List.of(), leaves);
        assertThat(HaloCliCatalog.commands(catalog.help(List.of()).block()))
                .contains(
                        "auth",
                        "post",
                        "single-page",
                        "search",
                        "plugin",
                        "theme",
                        "attachment",
                        "backup",
                        "moment",
                        "comment",
                        "notification",
                        "shop",
                        "completion");
        assertThat(leaves.size()).isGreaterThan(80);
        for (List<String> path : leaves) {
            var invocation = catalog.resolve(Map.of("arguments", path)).block();
            assertThat(invocation.path()).isEqualTo(path);
            assertThat(invocation.arguments()).startsWith(path.toArray(String[]::new));
        }
        for (List<String> path : List.of(
                List.of("comment", "list"),
                List.of("comment", "reply", "list"),
                List.of("post", "list"),
                List.of("moment", "list"))) {
            var input = new ArrayList<>(path);
            input.add("--size=1");
            var invocation = catalog.resolve(Map.of("arguments", input)).block();
            var result = cli.invoke(invocation.arguments(), null).block();
            assertThat(result.exitCode()).as(path.toString()).isZero();
            assertThat(HaloCliOutput.pretty(HaloCliOutput.sanitize(result.output())))
                    .contains("total", "items");
        }
        System.out.println(
                "Native CLI verified: " + leaves.size() + " command contracts, four real read-only list queries.");
    }

    private void visit(HaloCliCatalog catalog, List<String> path, List<List<String>> leaves) {
        String help = catalog.help(path).block();
        List<String> children = HaloCliCatalog.commands(help);
        if (children.isEmpty()) {
            leaves.add(path);
            return;
        }
        for (String child : children) {
            var nested = new ArrayList<>(path);
            nested.add(child);
            visit(catalog, nested, leaves);
        }
    }
}
