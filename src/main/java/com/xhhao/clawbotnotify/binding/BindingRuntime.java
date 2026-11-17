package com.xhhao.clawbotnotify.binding;

import com.xhhao.clawbotnotify.channel.WechatReceiver;
import com.xhhao.clawbotnotify.command.CommentReplyExecutor;
import com.xhhao.clawbotnotify.comment.CommentDiscovery;
import com.xhhao.clawbotnotify.comment.ReplyDiscovery;
import com.xhhao.clawbotnotify.comment.ReplyProcessingSynchronizer;
import com.xhhao.clawbotnotify.delivery.OutboundDeliveryWorker;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** Owns worker subscriptions and invalidates old runs before rebind, shutdown or recovery. */
@Component
public class BindingRuntime {
    private final WechatReceiver receiver;
    private final OutboundDeliveryWorker deliveries;
    private final CommentDiscovery comments;
    private final ReplyDiscovery replyNotifications;
    private final CommentReplyExecutor replies;
    private final ReplyProcessingSynchronizer processing;
    private Disposable discovering;
    private Disposable receiving;
    private Disposable sending;
    private long epoch;

    public BindingRuntime(
            WechatReceiver receiver,
            OutboundDeliveryWorker deliveries,
            CommentDiscovery comments,
            CommentReplyExecutor replies,
            ReplyProcessingSynchronizer processing,
            ReplyDiscovery replyNotifications) {
        this.receiver = receiver;
        this.deliveries = deliveries;
        this.comments = comments;
        this.replies = replies;
        this.processing = processing;
        this.replyNotifications = replyNotifications;
    }

    public synchronized void start() {
        dispose();
        long run = ++epoch;
        receiving = Mono.defer(() -> receiver.tick(() -> current(run)))
                .then(Mono.delay(Duration.ofMillis(250)))
                .repeat()
                .subscribe(ignored -> {}, ignored -> {});
        discovering = Mono.defer(() -> comments.tick(() -> current(run)))
                .onErrorResume(error -> Mono.empty())
                .then(Mono.defer(() -> replyNotifications.tick(() -> current(run)))
                        .onErrorResume(error -> Mono.empty()))
                .then(Mono.defer(() -> processing.tick(() -> current(run))).onErrorResume(error -> Mono.empty()))
                .then(Mono.delay(Duration.ofSeconds(10)))
                .repeat()
                .subscribe(ignored -> {}, ignored -> {});
        sending = Mono.defer(() -> deliveries.tick(() -> current(run)))
                .then(Mono.delay(Duration.ofSeconds(1)))
                .repeat()
                .subscribe(ignored -> {}, ignored -> {});
    }

    public Mono<Void> stop() {
        synchronized (this) {
            ++epoch;
            dispose();
        }
        return deliveries.recoverInterrupted().then(replies.recoverInterrupted());
    }

    public Mono<Void> recover() {
        return stop();
    }

    private void dispose() {
        if (receiving != null) receiving.dispose();
        if (sending != null) sending.dispose();
        if (discovering != null) discovering.dispose();
    }

    private synchronized boolean current(long run) {
        return run == epoch;
    }
}
