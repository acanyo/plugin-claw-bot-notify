package com.xhhao.clawbotnotify.assistant.review;

import java.util.LinkedHashMap;
import java.util.Map;

/** Apply bounded incremental edits to the exact saved article, without reconstructing untouched text. */
public final class DraftEdits {
    private DraftEdits() {}

    public static Map<String, Object> apply(Map<String, Object> previous, Map<String, Object> changes) {
        var result = new LinkedHashMap<>(previous);
        if (changes.containsKey("content") && (changes.containsKey("append") || changes.containsKey("find")))
            throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        if (changes.containsKey("append") && changes.containsKey("find"))
            throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        if (changes.containsKey("find") != changes.containsKey("replace"))
            throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        String content = String.valueOf(previous.getOrDefault("content", ""));
        if (changes.containsKey("append")) content += text(changes.get("append"));
        else if (changes.containsKey("find")) {
            String find = text(changes.get("find"));
            int at = content.indexOf(find);
            if (find.isEmpty() || at < 0 || content.indexOf(find, at + 1) >= 0)
                throw new IllegalStateException("AI_EDIT_TARGET_AMBIGUOUS");
            content = content.substring(0, at) + text(changes.get("replace")) + content.substring(at + find.length());
        } else if (changes.containsKey("content")) content = text(changes.get("content"));
        if (DraftReview.count(content) > DraftReview.MAX_ARTICLE || content.isBlank())
            throw new IllegalStateException("AI_PREVIEW_TOO_LONG");
        result.put("content", content);
        if (changes.containsKey("title")) result.put("title", text(changes.get("title")));
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalStateException("AI_TOOL_INPUT_INVALID");
        return text;
    }
}
