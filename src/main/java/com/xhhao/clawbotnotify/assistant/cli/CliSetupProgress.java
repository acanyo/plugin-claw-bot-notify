package com.xhhao.clawbotnotify.assistant.cli;

/** Download bytes refer to the current artifact; package counts cover the locked CLI distribution. */
public record CliSetupProgress(
        String stage, long downloadedBytes, long totalBytes, int completedPackages, int totalPackages) {
    public static CliSetupProgress stage(String name) {
        return new CliSetupProgress(name, 0, 0, 0, 0);
    }
}
