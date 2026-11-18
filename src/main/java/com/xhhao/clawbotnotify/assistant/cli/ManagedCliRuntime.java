package com.xhhao.clawbotnotify.assistant.cli;

import com.xhhao.clawbotnotify.storage.ClawBotDataDirectory;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Inspection is automatic; all downloads require the explicit setup action. */
@Component
public class ManagedCliRuntime {
    private final Path root;
    private final String external;
    private final NodeRuntimeInstaller nodes;
    private final CliDistributionInstaller cli;
    private final CliEnvironmentDetector detector;
    private volatile String state = "IDLE";
    private volatile String errorCode;
    private volatile CliEnvironment environment;
    private volatile CliSetupProgress progress = CliSetupProgress.stage("IDLE");
    private volatile List<String> readyCommand;
    private volatile boolean active;
    private volatile boolean stopped;
    private volatile Thread worker;
    private reactor.core.Disposable running;

    @org.springframework.beans.factory.annotation.Autowired
    public ManagedCliRuntime(ClawBotDataDirectory data) {
        this(
                data.root().resolve("cli"),
                System.getenv("CLAWBOT_HALO_CLI_EXECUTABLE"),
                new NodeRuntimeInstaller(),
                new CliDistributionInstaller(),
                new CliEnvironmentDetector());
    }

    ManagedCliRuntime(
            Path root,
            String external,
            NodeRuntimeInstaller nodes,
            CliDistributionInstaller cli,
            CliEnvironmentDetector detector) {
        this.root = root;
        this.external = external == null || external.isBlank() ? null : external;
        this.nodes = nodes;
        this.cli = cli;
        this.detector = detector;
        if (this.external != null) state = "EXTERNAL";
    }

    public boolean managed() {
        return external == null;
    }

    public record Status(
            String mode,
            String state,
            String cliVersion,
            String nodeVersion,
            String errorCode,
            CliEnvironment environment,
            CliSetupProgress progress) {}

    public Status status() {
        return new Status(
                managed() ? "MANAGED" : "EXTERNAL",
                state,
                managed() ? CliDistributionInstaller.VERSION : null,
                environment == null ? null : environment.nodeVersion(),
                errorCode,
                environment,
                progress);
    }

    public synchronized void start() {
        if (stopped) {
            stopped = false;
            state = managed() ? "IDLE" : "EXTERNAL";
        }
        if (state.equals("IDLE")) detect();
    }

    public synchronized void detect() {
        launch(false);
    }
    /** Compatibility for the earlier retry endpoint; still requires an explicit POST. */
    public synchronized void retry() {
        install();
    }

    public synchronized void install() {
        launch(true);
    }

    public synchronized void stop() {
        stopped = true;
        if (running != null) running.dispose();
        if (worker != null) worker.interrupt();
    }

    public Mono<List<String>> command() {
        if (!managed()) return Mono.just(List.of(external));
        if (state.equals("READY") && readyCommand != null) return Mono.just(readyCommand);
        return Mono.error(new IllegalStateException(
                errorCode != null ? errorCode : active ? "CLI_PREPARING" : "CLI_SETUP_REQUIRED"));
    }

    private void launch(boolean install) {
        if (!managed() || active || stopped) return;
        active = true;
        errorCode = null;
        state = "DETECTING";
        progress = CliSetupProgress.stage(state);
        running = Mono.fromRunnable(() -> {
                    worker = Thread.currentThread();
                    try {
                        prepare(install);
                    } catch (Exception error) {
                        throw new IllegalStateException(safeError(error));
                    } finally {
                        worker = null;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> {
                    if (!stopped) {
                        errorCode = safeError(error);
                        state = "FAILED";
                    }
                })
                .doFinally(ignored -> active = false)
                .subscribe(ignored -> {}, ignored -> {});
    }

    private void prepare(boolean install) throws Exception {
        if (stopped) throw new InterruptedException();
        environment = detector.detect(root, nodes, cli);
        if (!environment.canInstall()) {
            errorCode = environment.errorCode();
            state = "UNSUPPORTED";
            return;
        }
        Path node = environment.hasUsableNode() ? Path.of(environment.nodePath()) : null;
        Path entry = environment.cliInstalled() ? cli.cached(root) : null;
        if (!install) {
            if (node != null && entry != null) {
                CliProcessCheck.cli(node, entry);
                readyCommand = List.of(node.toString(), entry.toString());
                state = "READY";
            } else {
                state = "REQUIRED";
            }
            return;
        }
        ClawBotDataDirectory.directory(root);
        Path lock = root.resolve("install.lock");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            ClawBotDataDirectory.privatePermissions(lock, false);
            if (node == null) node = nodes.install(root, environment.platform(), this::update);
            update(CliSetupProgress.stage("CHECKING_NODE"));
            if (CliProcessCheck.nodeVersion(node) == null) throw new IllegalStateException("CLI_RUNTIME_UNAVAILABLE");
            entry = cli.install(root, this::update);
            update(CliSetupProgress.stage("CHECKING_CLI"));
            CliProcessCheck.cli(node, entry);
            if (stopped) throw new InterruptedException();
            environment = detector.detect(root, nodes, cli);
            readyCommand = List.of(node.toString(), entry.toString());
            progress = CliSetupProgress.stage("READY");
            state = "READY";
        }
    }

    private void update(CliSetupProgress next) {
        if (stopped || Thread.currentThread().isInterrupted()) throw new IllegalStateException("CLI_SETUP_CANCELLED");
        progress = next;
        state = next.stage();
    }

    private static String safeError(Throwable error) {
        String code = error.getMessage();
        return code != null && code.matches("CLI_[A-Z_]+")
                ? code
                : error instanceof java.nio.file.FileSystemException
                        ? "CLI_STORAGE_FAILED"
                        : error instanceof java.io.IOException ? "CLI_DOWNLOAD_FAILED" : "CLI_PREPARATION_FAILED";
    }
}
