package com.xhhao.clawbotnotify.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/** Durable references only. Message bodies are rendered with current permissions at dispatch time. */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "clawbot.xhhao.com",
        version = "v1alpha1",
        kind = "OutboundTask",
        plural = "outboundtasks",
        singular = "outboundtask")
public class OutboundTask extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum State {
        PENDING,
        SENDING,
        WAITING_CONTEXT,
        ACCEPTED,
        UNKNOWN,
        DEAD,
        CANCELLED
    }

    @Data
    @Schema(name = "OutboundTaskSpec")
    public static class Spec {
        private long generation;
        private String eventKey;
        private String clientId;
        private String targetUserId;
        private State state = State.PENDING;
        private Instant attemptedAt;
        private Instant finishedAt;
        private Instant cleanedAt;
        private String errorCode;
        private String upstreamMessageId;
        private String kind;
        private String commentName;
        /** Optional Reply selected within the parent comment; absent for top-level comments. */
        private String targetReplyName;

        private String shortCode;
        private String commandName;
        private Instant nextAttemptAt;
        private long contextVersion;
        private String quoteTextHash;
        private String quotedMessageId;
    }
}
