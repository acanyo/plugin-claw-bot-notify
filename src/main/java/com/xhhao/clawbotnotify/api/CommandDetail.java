package com.xhhao.clawbotnotify.api;

/** Plain text only. Content is loaded on demand for the binding owner in the current generation. */
public record CommandDetail(
        CommandView record,
        String submittedMarkdown,
        String commentMarkdown,
        String targetReplyMarkdown,
        String replyMarkdown,
        String contentErrorCode,
        com.xhhao.clawbotnotify.assistant.review.DraftReview.View preview) {
    public CommandDetail(
            CommandView record,
            String submittedMarkdown,
            String commentMarkdown,
            String targetReplyMarkdown,
            String replyMarkdown,
            String contentErrorCode) {
        this(record, submittedMarkdown, commentMarkdown, targetReplyMarkdown, replyMarkdown, contentErrorCode, null);
    }
}
