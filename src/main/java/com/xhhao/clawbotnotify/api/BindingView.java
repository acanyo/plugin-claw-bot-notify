package com.xhhao.clawbotnotify.api;

import java.time.Instant;

/** Deliberately excludes tokens, cursors, raw IDs, upstream bodies and Secret names. */
public record BindingView(
        String id,
        long generation,
        String haloUsername,
        String botIdentity,
        String ownerIdentity,
        String connectionState,
        boolean enabled,
        boolean credentialStorageReady,
        boolean haloCredentialConfigured,
        String qrState,
        String qrContent,
        Instant qrExpiresAt,
        Instant lastPollAt,
        Instant lastAcceptedAt,
        Instant contextReceivedAt,
        String errorCode,
        String protocolVersion) {}
