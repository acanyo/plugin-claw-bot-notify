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
        kind = "WechatBinding",
        plural = "wechatbindings",
        singular = "wechatbinding")
public class WechatBinding extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    private Status status = new Status();

    public enum ConnectionState {
        BINDING,
        AWAITING_ACTIVATION,
        AWAITING_MESSAGE,
        ACTIVE,
        DEGRADED,
        REAUTH_REQUIRED,
        DISABLED
    }

    @Data
    @Schema(name = "WechatBindingSpec")
    public static class Spec {
        private String haloUsername;
        private String botId;
        private String ownerWechatUserId;
        private String apiBaseUrl;
        private long generation;
        private boolean enabled;
        private Instant notificationStartAt;
        private String credentialRef;
        private boolean haloCredentialConfigured;
        private String bindingRequestHash;
    }

    @Data
    @Schema(name = "WechatBindingStatus")
    public static class Status {
        private ConnectionState connectionState = ConnectionState.BINDING;
        private Instant contextReceivedAt;
        private Instant lastPollAt;
        private Instant lastAcceptedAt;
        private String lastErrorCode;
    }
}
