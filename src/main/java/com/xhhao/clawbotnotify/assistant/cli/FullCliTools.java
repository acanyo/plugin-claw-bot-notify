package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.support.StableIds;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.infra.ExternalUrlSupplier;

/** Native CLI bridge. Every effect is staged, and confirmed effects share the durable single-dispatch ledger. */
public final class FullCliTools {
    private final HaloCliProcess cli;
    private final HaloCliCatalog catalog;
    private final AssistantDrafts drafts;
    private final CredentialStore secrets;
    private final ExternalUrlSupplier externalUrl;
    private final ObjectMapper json = new ObjectMapper();

    public FullCliTools(
            HaloCliProcess cli, AssistantDrafts drafts, CredentialStore secrets, ExternalUrlSupplier externalUrl) {
        this.cli = cli;
        this.catalog = new HaloCliCatalog(cli);
        this.drafts = drafts;
        this.secrets = secrets;
        this.externalUrl = externalUrl;
    }

    public Mono<Object> help(InboundCommand command, Map<String, Object> input) {
        Object path = input.getOrDefault("path", List.of());
        if (!(path instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)))
            return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
        return timed(command, catalog.help(list.stream().map(String.class::cast).toList()))
                .map(text -> (Object) Map.of("help", text));
    }

    public Mono<Object> execute(
            InboundCommand command, Map<String, Object> input, boolean writes, Supplier<Mono<Void>> preflight) {
        return timed(command, catalog.resolve(input)).flatMap(invocation -> {
            if (cli.managed()
                    && invocation.write()
                    && invocation.path().getFirst().equals("auth"))
                return Mono.error(new IllegalStateException("CLI_AUTH_MANAGED"));
            if (invocation.write() && !writes) return Mono.error(new IllegalStateException("AI_WRITES_DISABLED"));
            return site(invocation, preflight).then(Mono.defer(() -> {
                if (!invocation.write()) return run(command, invocation);
                return drafts.stage(
                        command, "halo_cli", parameters(invocation), StableIds.hash(invocation.contract()), false);
            }));
        });
    }

    public Mono<Object> stageRevision(InboundCommand command, Map<String, Object> input) {
        return timed(command, catalog.resolveFresh(input))
                .flatMap(invocation -> drafts.stage(
                        command, "halo_cli", parameters(invocation), StableIds.hash(invocation.contract()), false));
    }

    /** Resolve every command before starting any process; the batch cannot stage or execute writes. */
    public Mono<Object> readBatch(InboundCommand command, Map<String, Object> input, Supplier<Mono<Void>> preflight) {
        if (!Set.of("commands").containsAll(input.keySet())
                || !(input.get("commands") instanceof List<?> commands)
                || commands.isEmpty()) return Mono.error(new IllegalStateException("AI_TOOL_INPUT_INVALID"));
        return timed(
                        command,
                        Flux.fromIterable(commands)
                                .flatMapSequential(args -> catalog.resolve(Map.of("arguments", args)), 4, 1)
                                .collectList())
                .flatMap(plan -> {
                    if (plan.stream().anyMatch(HaloCliCatalog.Invocation::write))
                        return Mono.error(new IllegalStateException("AI_BATCH_READ_ONLY"));
                    return preflight
                            .get()
                            .thenMany(Flux.fromIterable(plan)
                                    .flatMapSequential(
                                            invocation -> run(command, invocation)
                                                    .map(result -> Map.of(
                                                            "arguments", invocation.arguments(), "result", result))
                                                    .onErrorResume(error -> Mono.just(Map.of(
                                                            "arguments", invocation.arguments(),
                                                            "result",
                                                                    Map.of(
                                                                            "success",
                                                                            false,
                                                                            "errorCode",
                                                                            AssistantTools.safeCode(error))))),
                                            4,
                                            1))
                            .collectList()
                            .map(results -> (Object) Map.of("results", results));
                });
    }

    public Mono<Object> confirmed(InboundCommand command, AssistantDrafts.Draft draft, Supplier<Mono<Void>> preflight) {
        return confirmed(command, draft, preflight, ignored -> Mono.empty());
    }

    public Mono<Object> confirmed(
            InboundCommand command,
            AssistantDrafts.Draft draft,
            Supplier<Mono<Void>> preflight,
            java.util.function.Function<HaloCliCatalog.Invocation, Mono<Object>> publisher) {
        return timed(command, catalog.resolveFresh(draft.input())).flatMap(invocation -> {
            if (cli.managed()
                    && invocation.write()
                    && invocation.path().getFirst().equals("auth"))
                return Mono.error(new IllegalStateException("CLI_AUTH_MANAGED"));
            if (draft.fingerprint() != null && !draft.fingerprint().equals(StableIds.hash(invocation.contract())))
                return Mono.error(new IllegalStateException("CLI_CONTRACT_CHANGED"));
            // Includes exact stdin; a second subscriber cannot dispatch even if native execution failed.
            String fingerprint = StableIds.hash(HaloCliOutput.pretty(parameters(invocation)));
            String ref = command.getSpec().getBodyRef();
            return site(invocation, preflight).then(secrets.read(ref)).flatMap(values -> {
                if (values.containsKey("mutation")) {
                    if (fingerprint.equals(values.get("mutation")) && values.containsKey("mutationResult")) {
                        try {
                            return Mono.just(json.readValue(values.get("mutationResult"), Object.class));
                        } catch (Exception error) {
                            return Mono.error(new IllegalStateException("CLI_RESULT_UNKNOWN"));
                        }
                    }
                    return Mono.error(new IllegalStateException("AI_WRITE_ALREADY_CLAIMED"));
                }
                return secrets.update(ref, data -> {
                            if (data.containsKey("mutation"))
                                throw new IllegalStateException("AI_WRITE_ALREADY_CLAIMED");
                            data.put("mutation", fingerprint);
                            data.put("mutationTool", "halo_cli");
                            data.put("mutationName", String.join(" ", invocation.path()));
                        })
                        .then(Mono.defer(() ->
                                publisher.apply(invocation).switchIfEmpty(Mono.defer(() -> run(command, invocation)))))
                        .flatMap(result -> {
                            if (result instanceof Map<?, ?> map && !Boolean.TRUE.equals(map.get("success")))
                                return Mono.error(new IllegalStateException("CLI_RESULT_UNKNOWN"));
                            return secrets.update(ref, data -> data.put("mutationResult", HaloCliOutput.pretty(result)))
                                    .thenReturn(result);
                        });
            });
        });
    }

    private Mono<Void> site(HaloCliCatalog.Invocation invocation, Supplier<Mono<Void>> preflight) {
        if (!invocation.path().getFirst().equals("auth")) return preflight.get();
        if (invocation.path().equals(List.of("auth", "login"))) {
            String url = option(invocation.arguments(), "--url");
            try {
                if (url == null
                        || !URI.create(url)
                                .normalize()
                                .toString()
                                .replaceAll("/+$", "")
                                .equals(externalUrl
                                        .getRaw()
                                        .toURI()
                                        .normalize()
                                        .toString()
                                        .replaceAll("/+$", "")))
                    return Mono.error(new IllegalStateException("CLI_SITE_MISMATCH"));
            } catch (Exception error) {
                return Mono.error(new IllegalStateException("CLI_SITE_MISMATCH"));
            }
        }
        return Mono.empty();
    }

    private static String option(List<String> args, String key) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--")) break;
            if (args.get(i).startsWith(key + "=")) return args.get(i).substring(key.length() + 1);
            if (args.get(i).equals(key) && i + 1 < args.size()) return args.get(i + 1);
        }
        return null;
    }

    private Mono<Object> run(InboundCommand command, HaloCliCatalog.Invocation invocation) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return cli.invoke(invocation.arguments(), invocation.stdin())
                    .map(result -> (Object) Map.of(
                            "success", result.exitCode() == 0,
                            "exitCode", result.exitCode(),
                            "output", HaloCliOutput.sanitize(result.output()),
                            "stderr", HaloCliOutput.text(result.stderr())))
                    .doOnEach(signal -> {
                        if (signal.isOnNext() || signal.isOnError())
                            synchronized (command) {
                                command.getSpec()
                                        .setCliDurationMs((command.getSpec().getCliDurationMs() == null
                                                        ? 0
                                                        : command.getSpec().getCliDurationMs())
                                                + (System.nanoTime() - start) / 1_000_000);
                            }
                    });
        });
    }

    private static <T> Mono<T> timed(InboundCommand command, Mono<T> action) {
        return Mono.defer(() -> {
            long start = System.nanoTime();
            return action.doOnEach(signal -> {
                if (signal.isOnNext() || signal.isOnError())
                    synchronized (command) {
                        command.getSpec()
                                .setCliDurationMs((command.getSpec().getCliDurationMs() == null
                                                ? 0
                                                : command.getSpec().getCliDurationMs())
                                        + (System.nanoTime() - start) / 1_000_000);
                    }
            });
        });
    }

    private static Map<String, Object> parameters(HaloCliCatalog.Invocation invocation) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("arguments", invocation.arguments());
        if (invocation.stdin() != null) parameters.put("stdin", invocation.stdin());
        return parameters;
    }
}
