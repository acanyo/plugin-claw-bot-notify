package com.xhhao.clawbotnotify.comment;

import java.time.Duration;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

@Component
public class ReplyProcessingReconciler implements Reconciler<Reconciler.Request> {
    private final ReplyProcessingSynchronizer synchronizer;
    private final ReplyDiscovery discovery;

    public ReplyProcessingReconciler(ReplyProcessingSynchronizer synchronizer, ReplyDiscovery discovery) {
        this.synchronizer = synchronizer;
        this.discovery = discovery;
    }

    @Override
    public Result reconcile(Request request) {
        try {
            discovery
                    .observe(request.name())
                    .then(synchronizer.observe(request.name()))
                    .block(Duration.ofSeconds(30));
            return Result.doNotRetry();
        } catch (RuntimeException error) {
            return Result.requeue(Duration.ofSeconds(10));
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new Reply())
                .syncAllOnStart(false)
                .workerCount(1)
                .build();
    }
}
