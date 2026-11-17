package com.xhhao.clawbotnotify.api;

import java.time.Instant;
import java.util.List;

/** List rows contain references and results, never decrypted bodies or credential references. */
public record CommandView(
        String id,
        long generation,
        String type,
        String state,
        String writeState,
        String commentName,
        String targetReplyName,
        String shortCode,
        String commentState,
        String replyName,
        Instant receivedAt,
        Instant recordedAt,
        String errorCode,
        String receiptState,
        String receiptErrorCode,
        boolean reviewRequired,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        Long modelDurationMs,
        Long cliDurationMs,
        String modelName,
        Instant cleanedAt) {
    @io.swagger.v3.oas.annotations.media.Schema(name = "CommandPage")
    public record Page(int page, int size, long total, List<CommandView> items) {}
}
