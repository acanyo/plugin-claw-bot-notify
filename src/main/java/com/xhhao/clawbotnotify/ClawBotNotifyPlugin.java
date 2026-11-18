package com.xhhao.clawbotnotify;

import com.xhhao.clawbotnotify.assistant.runtime.AssistantMaintenance;
import com.xhhao.clawbotnotify.assistant.summary.BlogSummaryScheduler;
import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.model.CommentLink;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.exception.SchemeNotFoundException;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * Plugin main class to manage the lifecycle of the plugin.
 *
 * <p>This class must be public and have a public constructor.
 *
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.
 *
 * @author Handsome
 * @since 1.0.0
 */
@Component
public class ClawBotNotifyPlugin extends BasePlugin {
    private static final Logger log = LoggerFactory.getLogger(ClawBotNotifyPlugin.class);
    private final SchemeManager schemes;
    private final BindingService bindings;
    private final AssistantMaintenance maintenance;
    private final BlogSummaryScheduler summaryScheduler;
    private final com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime cliRuntime;

    public ClawBotNotifyPlugin(
            PluginContext pluginContext,
            SchemeManager schemes,
            BindingService bindings,
            AssistantMaintenance maintenance,
            BlogSummaryScheduler summaryScheduler,
            com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime cliRuntime) {
        super(pluginContext);
        this.schemes = schemes;
        this.bindings = bindings;
        this.maintenance = maintenance;
        this.summaryScheduler = summaryScheduler;
        this.cliRuntime = cliRuntime;
    }

    @Override
    public void start() {
        schemes.register(WechatBinding.class, indexes -> {
            indexes.add(IndexSpecs.<WechatBinding, Long>single("spec.generation", Long.class)
                    .indexFunc(b -> b.getSpec().getGeneration()));
        });
        schemes.register(OutboundTask.class, indexes -> {
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.kind", String.class)
                    .indexFunc(t -> t.getSpec().getKind()));
            indexes.add(IndexSpecs.<OutboundTask, java.time.Instant>single("spec.cleanedAt", java.time.Instant.class)
                    .indexFunc(t -> t.getSpec().getCleanedAt()));
            indexes.add(IndexSpecs.<OutboundTask, java.time.Instant>single("spec.finishedAt", java.time.Instant.class)
                    .indexFunc(t -> t.getSpec().getFinishedAt()));
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.upstreamMessageId", String.class)
                    .indexFunc(t -> t.getSpec().getUpstreamMessageId()));
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.quoteTextHash", String.class)
                    .indexFunc(t -> t.getSpec().getQuoteTextHash()));
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.targetUserId", String.class)
                    .indexFunc(t -> t.getSpec().getTargetUserId()));
            indexes.add(IndexSpecs.<OutboundTask, Long>single("spec.contextVersion", Long.class)
                    .indexFunc(t -> t.getSpec().getContextVersion()));
            indexes.add(IndexSpecs.<OutboundTask, Long>single("spec.generation", Long.class)
                    .indexFunc(t -> t.getSpec().getGeneration()));
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.state", String.class)
                    .indexFunc(t -> t.getSpec().getState().name()));
            indexes.add(IndexSpecs.<OutboundTask, String>single("spec.eventKey", String.class)
                    .indexFunc(t -> t.getSpec().getEventKey()));
            indexes.add(
                    IndexSpecs.<OutboundTask, java.time.Instant>single("spec.nextAttemptAt", java.time.Instant.class)
                            .indexFunc(t -> t.getSpec().getNextAttemptAt()));
        });
        schemes.register(CommentLink.class, indexes -> {
            indexes.add(IndexSpecs.<CommentLink, Long>single("spec.generation", Long.class)
                    .indexFunc(t -> t.getSpec().getGeneration()));
            indexes.add(IndexSpecs.<CommentLink, String>single("spec.state", String.class)
                    .indexFunc(t -> t.getSpec().getState().name()));
            indexes.add(IndexSpecs.<CommentLink, String>single("spec.scopeCode", String.class)
                    .indexFunc(t -> t.getSpec().getScopeCode())
                    .unique(true));
            indexes.add(IndexSpecs.<CommentLink, String>single("spec.targetReplyName", String.class)
                    .indexFunc(t -> t.getSpec().getTargetReplyName()));
            indexes.add(IndexSpecs.<CommentLink, String>single("spec.commentName", String.class)
                    .indexFunc(t -> t.getSpec().getCommentName()));
        });
        schemes.register(InboundCommand.class, indexes -> {
            indexes.add(IndexSpecs.<InboundCommand, java.time.Instant>single("spec.cleanedAt", java.time.Instant.class)
                    .indexFunc(t -> t.getSpec().getCleanedAt()));
            indexes.add(IndexSpecs.<InboundCommand, java.time.Instant>single("spec.receivedAt", java.time.Instant.class)
                    .indexFunc(t -> t.getSpec().getReceivedAt()));
            indexes.add(IndexSpecs.<InboundCommand, String>single("spec.bodyRef", String.class)
                    .indexFunc(t -> t.getSpec().getBodyRef()));
            indexes.add(IndexSpecs.<InboundCommand, String>single("spec.type", String.class)
                    .indexFunc(command -> command.getSpec().getType().name()));
            indexes.add(IndexSpecs.<InboundCommand, String>single("spec.writeState", String.class)
                    .indexFunc(t -> t.getSpec().getWriteState() == null
                            ? null
                            : t.getSpec().getWriteState().name()));
            indexes.add(IndexSpecs.<InboundCommand, Long>single("spec.generation", Long.class)
                    .indexFunc(t -> t.getSpec().getGeneration()));
            indexes.add(IndexSpecs.<InboundCommand, String>single("spec.state", String.class)
                    .indexFunc(t -> t.getSpec().getState().name()));
        });
        // Fields used by bounded retention scans and shared-body checks.
        // Plugin lifecycle runs outside WebFlux request threads; finish recovery before accepting work.
        try {
            bindings.start().block(Duration.ofSeconds(15));

        } catch (RuntimeException e) {
            log.warn("ClawBot recovery paused; check credential storage and binding state");
        }
        maintenance.start();
        cliRuntime.start();
        summaryScheduler.start();
    }

    @Override
    public void stop() {
        cliRuntime.stop();
        summaryScheduler.stop();
        maintenance.stop();
        try {
            Mono.defer(bindings::stop)
                    // Halo may remove schemes before stopping plugins during application shutdown.
                    // Subscriptions are cancelled first; persisted SENDING tasks recover on next start.
                    .onErrorResume(SchemeNotFoundException.class, ignored -> Mono.empty())
                    .block(Duration.ofSeconds(15));
        } finally {
            schemes.unregister(Scheme.buildFromType(InboundCommand.class));
            schemes.unregister(Scheme.buildFromType(CommentLink.class));
            schemes.unregister(Scheme.buildFromType(OutboundTask.class));
            schemes.unregister(Scheme.buildFromType(WechatBinding.class));
        }
    }
}
