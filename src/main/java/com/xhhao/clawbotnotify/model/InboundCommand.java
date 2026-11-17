package com.xhhao.clawbotnotify.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "clawbot.xhhao.com",
        version = "v1alpha1",
        kind = "InboundCommand",
        plural = "inboundcommands",
        singular = "inboundcommand")
public class InboundCommand extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum Type {
        START,
        HELP,
        PENDING,
        VIEW,
        REPLY,
        APPROVE_REPLY,
        AI,
        AI_RESET,
        AI_PREVIEW,
        AI_MEDIA,
        AI_CONFIRM,
        AI_APPROVE,
        AI_CANCEL,
        MARK_HANDLED,
        IGNORE,
        QUOTE_UNRESOLVED,
        INVALID,
        UNSUPPORTED
    }

    public enum State {
        RECORDED,
        QUEUED
    }

    public enum WriteState {
        READY,
        EXECUTING,
        SUCCEEDED,
        AWAITING_APPROVAL,
        REJECTED,
        UNKNOWN,
        PARTIAL
    }

    @Data
    @Schema(name = "InboundCommandSpec")
    public static class Spec {
        private String bindingId = "primary";
        private long generation;
        private Type type;
        private String shortCode;
        private int page = 1;
        private Instant receivedAt;
        private State state = State.RECORDED;
        private String commentName;
        /** Optional Reply selected within the parent comment; absent for top-level comments. */
        private String targetReplyName;

        private String bodyRef;
        private String quotedMessageId;
        private WriteState writeState;
        private String replyName;
        private String errorCode;
        private String approvalOf;
        private String approvalCommand;
        private String previewOf;
        private Instant startedAt;
        private Instant finishedAt;
        private Long durationMs;
        private Long modelDurationMs;
        private Long cliDurationMs;
        private String modelName;
        private Instant cleanedAt;
    }
}
