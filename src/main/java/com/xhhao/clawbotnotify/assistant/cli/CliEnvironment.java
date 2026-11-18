package com.xhhao.clawbotnotify.assistant.cli;

import java.time.Instant;

public record CliEnvironment(
        Instant checkedAt,
        String system,
        String architecture,
        String platform,
        boolean container,
        boolean platformSupported,
        String dataDirectory,
        boolean storageWritable,
        String nodeSource,
        String nodeVersion,
        String nodePath,
        boolean cliInstalled,
        boolean canInstall,
        String errorCode) {
    public boolean hasUsableNode() {
        return nodePath != null && nodeVersion != null;
    }
}
