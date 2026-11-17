package com.xhhao.clawbotnotify.api;

import java.time.Instant;
import java.util.List;

public record DeliveryView(
        String id,
        long generation,
        String state,
        String kind,
        String shortCode,
        Instant attemptedAt,
        Instant finishedAt,
        String errorCode,
        String upstreamMessageId) {
    public record Page(int page, int size, long total, List<DeliveryView> items) {}
}
