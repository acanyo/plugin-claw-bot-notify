package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.xhhao.clawbotnotify.format.MessageFormatting;
import com.xhhao.clawbotnotify.support.StableIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed command vocabulary; model input can only become values of known options. */
public final class HaloCliPolicy {
    private HaloCliPolicy() {}

    public static Invocation invocation(String tool, Map<String, Object> input, String command) {
        boolean write = tool.equals("moment_create")
                || tool.equals("post_create_draft")
                || tool.equals("post_update_draft")
                || tool.equals("post_publish_draft");
        Set<String> allowed =
                switch (tool) {
                    case "post_list", "moment_list" -> Set.of("page", "size");
                    case "post_get", "moment_get" -> Set.of("name");
                    case "moment_create" -> Set.of("content", "visible");
                    case "post_create_draft" -> Set.of("title", "content");
                    case "post_update_draft", "post_publish_draft" -> Set.of("name", "title", "content");
                    default -> throw invalid();
                };
        if (!allowed.containsAll(input.keySet())) throw invalid();
        String kind = tool.startsWith("post_") ? "post" : "moment";
        var args = new ArrayList<String>();
        args.add(kind);
        String name = write ? "clawbot-" + StableIds.hash(command, tool).substring(0, 32) : null;
        if (tool.equals("post_update_draft") || tool.equals("post_publish_draft")) {
            name = text(input, "name", 253);
            if (!name.matches("[a-z0-9][a-z0-9.-]*")) throw invalid();
            args.add("update");
            args.add(name);
            if (tool.equals("post_update_draft")) {
                args.add("--title=" + text(input, "title", 200));
                args.add("--content=" + text(input, "content", 60000));
                args.add("--raw-type=markdown");
                args.add("--publish=false");
            } else args.add("--publish=true");
        } else if (tool.endsWith("_list")) {
            args.add("list");
            args.add("--page=" + number(input, "page", 1, 100));
            args.add("--size=" + number(input, "size", 5, 10));
        } else if (tool.endsWith("_get")) {
            args.add("get");
            String target = text(input, "name", 253);
            if (!target.matches("[a-z0-9][a-z0-9.-]*")) throw invalid();
            args.add(target);
        } else {
            args.add("create");
            args.add("--name=" + name);
            String content = text(input, "content", kind.equals("post") ? 60000 : 4000);
            if (kind.equals("moment") && content.codePointCount(0, content.length()) > 2000) throw invalid();
            // Moment CLI accepts HTML; render plaintext ourselves so generated markup cannot create active content.
            args.add("--content=" + (kind.equals("moment") ? MessageFormatting.replyHtml(content) : content));
            if (kind.equals("post")) {
                args.add("--title=" + text(input, "title", 200));
                args.add("--slug=" + name);
                args.add("--raw-type=markdown");
                args.add("--publish=false");
            } else {
                String visible = input.containsKey("visible") ? text(input, "visible", 7) : "PUBLIC";
                if (!Set.of("PUBLIC", "PRIVATE").contains(visible)) throw invalid();
                args.add("--visible=" + visible);
                args.add("--approved=true");
            }
        }
        return new Invocation(tool, kind, List.copyOf(args), write, name);
    }

    public static Object result(Invocation invocation, JsonNode output) {
        if (invocation.tool().endsWith("_list")) {
            var rows = new ArrayList<Object>();
            for (var item : output.path("items")) {
                if (rows.size() >= 10) break;
                rows.add(resource(invocation.kind(), item, false));
            }
            return Map.of("items", rows, "total", output.path("total").asLong());
        }
        JsonNode root = unwrap(invocation.kind(), output);
        if (invocation.write()) {
            String actualName = root.path("metadata").path("name").asText();
            boolean valid = actualName.matches("[a-z0-9][a-z0-9.-]{0,252}");
            if (invocation.tool().equals("post_update_draft")
                    || invocation.tool().equals("post_publish_draft")) {
                valid &= invocation.name().equals(actualName)
                        && root.path("spec").path("publish").isBoolean()
                        && root.path("spec").path("publish").asBoolean()
                                == invocation.tool().equals("post_publish_draft");
            } else if (invocation.kind().equals("post")) {
                // Halo's draft endpoint assigns metadata.name; the deterministic slug identifies this request.
                valid &= invocation.name().equals(root.path("spec").path("slug").asText())
                        && root.path("spec").path("publish").isBoolean()
                        && !root.path("spec").path("publish").asBoolean();
            } else valid &= invocation.name().equals(actualName);
            if (!valid) throw new IllegalStateException("CLI_RESULT_UNKNOWN");
        }
        return resource(invocation.kind(), output, true);
    }

    private static Object resource(String kind, JsonNode output, boolean full) {
        JsonNode root = unwrap(kind, output);
        var spec = root.path("spec");
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("name", root.path("metadata").path("name").asText());
        for (String field : List.of("creationTimestamp", "deletionTimestamp"))
            if (root.path("metadata").hasNonNull(field))
                result.put(field, root.path("metadata").path(field).asText());
        for (String field : List.of("publishTime", "releaseTime", "createTime", "updateTime"))
            if (spec.hasNonNull(field)) result.put(field, spec.path(field).asText());
        if (kind.equals("post")) {
            result.put("title", spec.path("title").asText());
            result.put("published", spec.path("publish").asBoolean());
            result.put("visible", spec.path("visible").asText());
            result.put("phase", root.path("status").path("phase").asText());
            result.put("permalink", root.path("status").path("permalink").asText());
            result.put(
                    "hasUnpublishedChanges",
                    !spec.path("headSnapshot")
                            .asText()
                            .equals(spec.path("releaseSnapshot").asText()));
            if (full)
                result.put(
                        "content",
                        MessageFormatting.truncate(
                                output.path("content").path("raw").asText(), 30000));
        } else {
            result.put("owner", spec.path("owner").asText());
            result.put("visible", spec.path("visible").asText());
            result.put(
                    "content",
                    MessageFormatting.truncate(spec.path("content").path("html").asText(), full ? 6000 : 300));
        }
        return result;
    }

    private static JsonNode unwrap(String kind, JsonNode output) {
        return output.has(kind) ? output.path(kind) : output;
    }

    private static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value)
                || value.isBlank()
                || value.length() > max
                || value.indexOf('\0') >= 0) throw invalid();
        return value;
    }

    private static int number(Map<String, Object> input, String key, int fallback, int max) {
        Object value = input.getOrDefault(key, fallback);
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()
                || number.intValue() < 1
                || number.intValue() > max) throw invalid();
        return number.intValue();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AI_TOOL_INPUT_INVALID");
    }

    public record Invocation(String tool, String kind, List<String> arguments, boolean write, String name) {}
}
