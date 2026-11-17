package com.xhhao.clawbotnotify.binding;

import java.time.Instant;
import reactor.core.Disposable;

/** Only the current short-lived QR transaction is kept in memory. Never serialized. */
final class LoginSession {
    final long generation;
    final String qr;
    final String content;
    final Instant expiresAt = Instant.now().plusSeconds(300);
    volatile String state = "WAITING_SCAN";
    volatile String base = com.xhhao.clawbotnotify.channel.WechatDestinationPolicy.DEFAULT_BASE;
    volatile String pendingPin;
    volatile String errorCode;
    Disposable polling;

    LoginSession(long generation, String qr, String content) {
        this.generation = generation;
        this.qr = qr;
        this.content = content;
    }

    void stop() {
        pendingPin = null;
        if (polling != null) polling.dispose();
    }
}
