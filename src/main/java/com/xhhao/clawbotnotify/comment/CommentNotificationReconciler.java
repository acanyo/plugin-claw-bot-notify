package com.xhhao.clawbotnotify.comment;

import java.time.Duration;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

@Component
public class CommentNotificationReconciler implements Reconciler<Reconciler.Request> {
    private final CommentDiscovery discovery;

    public CommentNotificationReconciler(CommentDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override
    public Result reconcile(Request request) {
        try {
            discovery.observe(request.name()).block(Duration.ofSeconds(10));
            return Result.doNotRetry();
        } catch (RuntimeException e) {
            return Result.requeue(Duration.ofSeconds(10));
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new Comment())
                .syncAllOnStart(false)
                .workerCount(1)
                .build();
    }
}
