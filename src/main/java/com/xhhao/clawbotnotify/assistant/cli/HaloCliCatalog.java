package com.xhhao.clawbotnotify.assistant.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/** Discovers native command contracts from the installed executable, including nested groups. */
public final class HaloCliCatalog {
    private static final Set<String> READS =
            Set.of("list", "get", "current", "status", "doctor", "inspect", "search", "daily");
    private static final Pattern FLAG = Pattern.compile(
            "(?<![\\w-])(--[a-z][a-z0-9-]*|-[a-zA-Z])(?:[ ,]+(--[a-z][a-z0-9-]*))?(?:\\s+(<[^>]+>|\\[[^]]+]))?");
    private final HaloCliProcess cli;
    private final Map<List<String>, Mono<String>> cache = new ConcurrentHashMap<>();

    public HaloCliCatalog(HaloCliProcess cli) {
        this.cli = cli;
    }

    public Mono<String> help(List<String> path) {
        if (path.size() > 8 || path.stream().anyMatch(part -> !part.matches("[a-z][a-z0-9-]*")))
            return Mono.error(new IllegalStateException("CLI_COMMAND_INVALID"));
        return verifyPath(path, 0).then(raw(path));
    }

    private Mono<Void> verifyPath(List<String> path, int index) {
        if (index == path.size()) return Mono.empty();
        return raw(path.subList(0, index)).flatMap(text -> {
            if (!commands(text).contains(path.get(index)))
                return Mono.error(new IllegalStateException("CLI_COMMAND_INVALID"));
            return verifyPath(path, index + 1);
        });
    }

    private Mono<String> raw(List<String> path) {
        return cache.computeIfAbsent(
                List.copyOf(path),
                key -> Mono.defer(() -> cli.help(key))
                        .cache(value -> Duration.ofMinutes(5), error -> Duration.ZERO, () -> Duration.ZERO));
    }

    public record Invocation(List<String> arguments, List<String> path, String stdin, boolean write, String contract) {}

    public Mono<Invocation> resolve(Map<String, Object> input) {
        if (!Set.of("arguments", "stdin").containsAll(input.keySet()))
            return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
        List<String> args = arguments(input.get("arguments"));
        Object body = input.get("stdin");
        if (body != null && !(body instanceof String))
            return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
        String stdin = (String) body;
        if (args.stream().mapToInt(String::length).sum() + (stdin == null ? 0 : stdin.length()) > 262144)
            return Mono.error(new IllegalStateException("CLI_INPUT_TOO_LARGE"));
        return leaf(args, List.of()).map(leaf -> prepare(args, leaf, stdin));
    }

    public Mono<Invocation> resolveFresh(Map<String, Object> input) {
        return resolve(input)
                .flatMap(invocation -> cli.help(invocation.path())
                        .map(help -> prepare(
                                arguments(input.get("arguments")),
                                new Leaf(invocation.path(), help),
                                invocation.stdin())));
    }

    public static List<String> arguments(Object value) {
        if (!(value instanceof List<?> list)
                || list.isEmpty()
                || list.size() > 128
                || list.stream().anyMatch(item -> !(item instanceof String text) || text.indexOf('\0') >= 0))
            throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        return list.stream().map(String.class::cast).toList();
    }

    private record Leaf(List<String> path, String help) {}

    private Mono<Leaf> leaf(List<String> args, List<String> path) {
        if (path.size() > 8) return Mono.error(new IllegalStateException("CLI_COMMAND_INVALID"));
        return raw(path).flatMap(text -> {
            List<String> children = commands(text);
            if (children.isEmpty()) return Mono.just(new Leaf(path, text));
            if (path.size() >= args.size() || !children.contains(args.get(path.size())))
                return Mono.error(new IllegalStateException("CLI_COMMAND_INVALID"));
            return leaf(args, args.subList(0, path.size() + 1));
        });
    }

    private Invocation prepare(List<String> original, Leaf leaf, String stdin) {
        Map<String, Boolean> options = options(leaf.help());
        boolean auth = leaf.path().getFirst().equals("auth");
        var args = new ArrayList<>(original);
        boolean hasProfile = false;
        boolean hasUrl = false;
        for (int i = leaf.path().size(); i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--")) break;
            if (!arg.startsWith("-")) continue;
            String flag = arg.split("=", 2)[0];
            if (!options.containsKey(flag) || flag.equals("--help") || flag.equals("-h"))
                throw new IllegalStateException("CLI_OPTION_INVALID");
            String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : null;
            if (options.get(flag) && value == null) {
                if (++i >= args.size()) throw new IllegalStateException("CLI_OPTION_INVALID");
                value = args.get(i);
                if (value.startsWith("-")) throw new IllegalStateException("CLI_OPTION_INVALID");
            }
            if (flag.equals("--url")) {
                if (hasUrl) throw new IllegalStateException("CLI_OPTION_INVALID");
                hasUrl = true;
            }
            if (flag.equals("--profile")) {
                if (hasProfile) throw new IllegalStateException("CLI_OPTION_INVALID");
                checkProfile(auth, value);
                hasProfile = true;
            }
        }
        if (options.containsKey("--profile") && !hasProfile) args.add(flagIndex(args), "--profile=" + cli.profile());
        if (options.containsKey("--json")
                && args.stream().noneMatch(arg -> arg.equals("--json") || arg.startsWith("--json=")))
            args.add(flagIndex(args), "--json");
        boolean write = !READS.contains(leaf.path().getLast())
                || stdin != null
                || args.stream()
                        .anyMatch(arg -> arg.equals("--output")
                                || arg.startsWith("--output=")
                                || arg.equals("--file")
                                || arg.startsWith("--file="));
        if (write) {
            for (String flag : List.of("--force", "--yes")) {
                if (options.containsKey(flag)
                        && args.stream().noneMatch(arg -> arg.equals(flag) || arg.startsWith(flag + "=")))
                    args.add(flagIndex(args), flag);
            }
        }
        return new Invocation(List.copyOf(args), leaf.path(), stdin, write, leaf.help());
    }

    private static int flagIndex(List<String> args) {
        int end = args.indexOf("--");
        return end < 0 ? args.size() : end;
    }

    private void checkProfile(boolean auth, String value) {
        if (!auth && !cli.profile().equals(value)) throw new IllegalStateException("CLI_PROFILE_MISMATCH");
    }

    static List<String> commands(String text) {
        var result = new ArrayList<String>();
        boolean section = false;
        for (String line : text.split("\\R")) {
            if (line.equals("Commands:")) {
                section = true;
                continue;
            }
            if (section && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) break;
            if (section && line.matches("^\\s{2}[a-z][a-z0-9-]*.*"))
                result.add(line.strip().split("\\s+", 2)[0]);
        }
        return result;
    }

    static Map<String, Boolean> options(String text) {
        var result = new LinkedHashMap<String, Boolean>();
        boolean section = false;
        for (String line : text.split("\\R")) {
            if (line.equals("Options:")) {
                section = true;
                continue;
            }
            if (section && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) break;
            if (!section) continue;
            var match = FLAG.matcher(line);
            if (match.find()) {
                boolean value = match.group(3) != null;
                result.put(match.group(1), value);
                if (match.group(2) != null) result.put(match.group(2), value);
            }
        }
        return result;
    }
}
