package com.xhhao.clawbotnotify.assistant.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts.Draft;
import com.xhhao.clawbotnotify.support.StableIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Exact version identity, Unicode-safe preview parts and bounded line comparison. */
public final class DraftReview {
    public static final int MAX_ARTICLE = 30000;
    public static final int PART_SIZE = 1200;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private DraftReview() {}

    public record Change(String kind, String text) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "DraftPreview")
    public record View(
            String version,
            String tool,
            String title,
            int characters,
            List<String> parts,
            List<Change> changes,
            String beforeTitle,
            String confirmation) {}

    public static String version(Draft draft) {
        try {
            return StableIds.hash(JSON.writeValueAsString(draft)).substring(0, 12);
        } catch (Exception error) {
            throw new IllegalStateException("AI_PREVIEW_UNAVAILABLE");
        }
    }

    public static boolean longArticle(Draft draft) {
        return draft.tool().startsWith("post_")
                && count(String.valueOf(draft.input().getOrDefault("content", ""))) > 2000;
    }

    public static int count(String text) {
        return text.codePointCount(0, text.length());
    }

    public static List<String> parts(String content) {
        var result = new ArrayList<String>();
        int from = 0;
        while (from < content.length()) {
            int end = content.offsetByCodePoints(
                    from, Math.min(PART_SIZE, content.codePointCount(from, content.length())));
            if (end < content.length()) {
                int paragraph = content.lastIndexOf('\n', end - 1);
                if (paragraph > from + (end - from) / 2) end = paragraph + 1;
            }
            result.add(content.substring(from, end));
            from = end;
        }
        return result.isEmpty() ? List.of("") : List.copyOf(result);
    }

    public static View view(Draft draft, Map<String, Object> before) {
        String content = String.valueOf(draft.input().getOrDefault("content", ""));
        return new View(
                version(draft),
                draft.tool(),
                String.valueOf(draft.input().getOrDefault("title", "")),
                count(content),
                parts(content),
                diff(String.valueOf(before.getOrDefault("content", "")), content),
                String.valueOf(before.getOrDefault("title", "")),
                com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts.confirmation(draft));
    }

    public static List<Change> diff(String before, String after) {
        if (before.equals(after)) return List.of();
        String[] old = before.isEmpty() ? new String[0] : before.split("(?<=\\n)", -1);
        String[] next = after.isEmpty() ? new String[0] : after.split("(?<=\\n)", -1);
        int prefix = 0, suffix = 0;
        while (prefix < Math.min(old.length, next.length) && old[prefix].equals(next[prefix])) prefix++;
        while (suffix < Math.min(old.length, next.length) - prefix
                && old[old.length - 1 - suffix].equals(next[next.length - 1 - suffix])) suffix++;
        var changes = new ArrayList<Change>();
        if (prefix > 0) changes.add(new Change("UNCHANGED", String.join("", Arrays.copyOfRange(old, 0, prefix))));
        int n = old.length - prefix - suffix, m = next.length - prefix - suffix;
        if ((long) n * m <= 250000) {
            int[][] lengths = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; --i)
                for (int j = m - 1; j >= 0; --j)
                    lengths[i][j] = old[prefix + i].equals(next[prefix + j])
                            ? lengths[i + 1][j + 1] + 1
                            : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && old[prefix + i].equals(next[prefix + j])) {
                    changes.add(new Change("UNCHANGED", old[prefix + i++]));
                    j++;
                } else if (i < n && (j == m || lengths[i + 1][j] >= lengths[i][j + 1]))
                    changes.add(new Change("REMOVED", old[prefix + i++]));
                else changes.add(new Change("ADDED", next[prefix + j++]));
            }
        } else {
            if (n > 0)
                changes.add(
                        new Change("REMOVED", String.join("", Arrays.copyOfRange(old, prefix, old.length - suffix))));
            if (m > 0)
                changes.add(
                        new Change("ADDED", String.join("", Arrays.copyOfRange(next, prefix, next.length - suffix))));
        }
        if (suffix > 0)
            changes.add(
                    new Change("UNCHANGED", String.join("", Arrays.copyOfRange(old, old.length - suffix, old.length))));
        changes.removeIf(change -> change.text().isEmpty());
        return changes;
    }
}
