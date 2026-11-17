package com.xhhao.clawbotnotify.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;
import run.halo.app.extension.Ref;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "clawbot.xhhao.com",
        version = "v1alpha1",
        kind = "CommentLink",
        plural = "commentlinks",
        singular = "commentlink")
public class CommentLink extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    public enum State {
        OPEN,
        ANSWERED,
        IGNORED,
        DELETED
    }

    @Data
    @Schema(name = "CommentLinkSpec")
    public static class Spec {
        private String bindingId = "primary";
        private long generation;
        private String shortCode;
        private String commentName;
        /** Optional Reply selected within the parent comment; absent for top-level comments. */
        private String targetReplyName;

        private Ref subjectRef;
        private State state = State.OPEN;

        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getScopeCode() {
            return bindingId + ":" + generation + ":" + shortCode;
        }
    }
}
