package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Executes an argument vector, without a shell. No raw process output enters error messages. */
@Component
public class HaloCliProcess {
    private static final int MAX_BYTES = 256 * 1024;
    private final String executable;
    private final String profile;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();
    private ManagedCliRuntime runtime;
    private ManagedCliCredentials credentials;

    @Autowired
    public HaloCliProcess(ManagedCliRuntime runtime, ManagedCliCredentials credentials) {
        this(
                value("CLAWBOT_HALO_CLI_EXECUTABLE", "halo"),
                value("CLAWBOT_HALO_CLI_PROFILE", "clawbot"),
                Duration.ofSeconds(40));
        this.runtime = runtime;
        this.credentials = credentials;
    }

    public HaloCliProcess(String executable, String profile, Duration timeout) {
        if (executable == null || executable.isBlank() || profile == null || !profile.matches("[A-Za-z0-9_.-]{1,80}"))
            throw new IllegalArgumentException("CLI_CONFIGURATION_INVALID");
        this.executable = executable;
        this.profile = profile;
        this.timeout = timeout;
    }

    public Mono<JsonNode> run(List<String> arguments) {
        var args = new ArrayList<>(arguments);
        args.add("--profile=" + profile);
        args.add("--json");
        return invoke(args, null).map(result -> {
            if (result.exitCode() != 0) throw new IllegalStateException("CLI_FAILED");
            if (!(result.output() instanceof JsonNode node) || !node.isContainerNode())
                throw new IllegalStateException("CLI_OUTPUT_INVALID");
            return node;
        });
    }

    public String profile() {
        return profile;
    }

    public boolean managed() {
        return runtime != null && runtime.managed();
    }

    public record Result(int exitCode, Object output, String stderr) {}

    public Mono<String> help(List<String> path) {
        var args = new ArrayList<>(path);
        args.add("--help");
        return invoke(args, null).map(result -> {
            if (result.exitCode() != 0 || !(result.output() instanceof String text))
                throw new IllegalStateException("CLI_HELP_UNAVAILABLE");
            return text;
        });
    }

    public Mono<Result> invoke(List<String> arguments, String stdin) {
        Mono<List<String>> prefix = runtime == null ? Mono.just(List.of(executable)) : runtime.command();
        boolean managed = runtime != null && runtime.managed();
        boolean help = arguments.contains("--help") || arguments.contains("--version");
        Mono<String> header = managed && !help ? credentials.header(profile) : Mono.just("{}");
        return prefix.flatMap(command -> header.flatMap(auth -> Mono.fromCallable(() -> blocking(
                                command, arguments, managed ? auth + "\n" + (stdin == null ? "" : stdin) : stdin))
                        .subscribeOn(Schedulers.boundedElastic())))
                .onErrorMap(error -> new IllegalStateException(safeCode(error)));
    }

    private Result blocking(List<String> prefix, List<String> arguments, String stdin) throws Exception {
        var command = new ArrayList<String>(prefix);
        command.addAll(arguments);
        Process process;
        try {
            var builder = new ProcessBuilder(command);
            Map<String, String> environment = builder.environment();
            environment.clear();
            for (var name : List.of(
                    "PATH",
                    "SystemRoot",
                    "WINDIR",
                    "HOME",
                    "XDG_CONFIG_HOME",
                    "XDG_RUNTIME_DIR",
                    "DBUS_SESSION_BUS_ADDRESS",
                    "LANG",
                    "LC_ALL",
                    "TMPDIR")) {
                String configured = System.getenv(name);
                if (configured != null) environment.put(name, configured);
            }
            String config = System.getenv("CLAWBOT_HALO_CLI_CONFIG_DIR");
            if (config != null && !config.isBlank()) environment.put("HALO_CLI_CONFIG_DIR", config);
            environment.put("NO_COLOR", "1");
            environment.put("CI", "true");
            process = builder.start();
        } catch (IOException error) {
            throw new IllegalStateException("CLI_NOT_AVAILABLE");
        }
        var output = new CompletableFuture<byte[]>();
        var errors = new CompletableFuture<byte[]>();
        read(process, process.getInputStream(), output);
        read(process, process.getErrorStream(), errors);
        Thread.startVirtualThread(() -> {
            try (var stream = process.getOutputStream()) {
                if (stdin != null) stream.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                /* CLI may exit before consuming stdin. */
            }
        });
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("CLI_TIMEOUT");
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            String stderr = new String(errors.get(2, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = json.readTree(bytes);
                if (parsed == null) parsed = "";
            } catch (Exception error) {
                parsed = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            return new Result(process.exitValue(), parsed, stderr);
        } finally {
            stop(process);
        }
    }

    private static void read(Process process, java.io.InputStream source, CompletableFuture<byte[]> output) {
        Thread.startVirtualThread(() -> {
            try (var stream = source) {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    output.completeExceptionally(new IllegalStateException("CLI_OUTPUT_TOO_LARGE"));
                    stop(process);
                } else output.complete(bytes);
            } catch (Exception error) {
                output.completeExceptionally(new IllegalStateException("CLI_FAILED"));
            }
        });
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    private static String safeCode(Throwable error) {
        while (error instanceof java.util.concurrent.ExecutionException && error.getCause() != null)
            error = error.getCause();
        String code = error.getMessage();
        return code != null && code.matches("(?:CLI_|BINDING_|CREDENTIAL_)[A-Z_]{1,60}") ? code : "CLI_FAILED";
    }

    private static String value(String name, String fallback) {
        String configured = System.getenv(name);
        return configured == null || configured.isBlank() ? fallback : configured;
    }
}
