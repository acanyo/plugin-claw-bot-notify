package com.xhhao.clawbotnotify.api;

import java.time.Instant;

public record AssistantRuntimeView(
        boolean enabled,
        boolean allowWrites,
        Instant checkedAt,
        Dependency model,
        Dependency cli,
        int retentionDays,
        int draftMinutes,
        Instant cleanupAt,
        int cleanedBodies,
        String cleanupErrorCode,
        com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime.Status cliSetup) {
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantDependency")
    public record Dependency(String name, boolean available, Long latencyMs, String errorCode) {}
}
