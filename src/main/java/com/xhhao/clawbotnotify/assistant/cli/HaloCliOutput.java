package com.xhhao.clawbotnotify.assistant.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keeps resource fields (including dates and totals), while removing credentials and private contact data. */
public final class HaloCliOutput {
    private static final ObjectMapper JSON = new ObjectMapper();

    private HaloCliOutput() {}

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("privatekey")
                || normalized.contains("apikey")
                || normalized.equals("authorization")
                || normalized.equals("cookie")
                || normalized.equals("setcookie")
                || normalized.equals("email")
                || normalized.equals("ip")
                || normalized.equals("ipaddress")
                || normalized.equals("phone")
                || normalized.equals("annotations");
    }

    public static Object sanitize(Object value) {
        if (value instanceof JsonNode node) return sanitize(JSON.convertValue(node, Object.class));
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) ->
                    result.put(String.valueOf(key), sensitive(String.valueOf(key)) ? "[已隐藏]" : sanitize(item)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(HaloCliOutput::sanitize).toList();
        if (value instanceof String text) return text(text);
        return value;
    }

    public static String text(String text) {
        return text.replaceAll("(?i)(Bearer|Basic)\\s+[A-Za-z0-9+/_.=-]+", "$1 [已隐藏]")
                .replaceAll("(?i)\\bpat_[A-Za-z0-9_.-]+", "[已隐藏]")
                .replaceAll("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[已隐藏]")
                .replaceAll(
                        "(?i)((?:token|password|secret|api[-_]?key|credential|signature)[\\w-]*[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&,}]+",
                        "$1[已隐藏]")
                .replaceAll("(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[已隐藏]@");
    }

    public static Map<String, Object> proposal(Map<String, Object> input) {
        var result = new LinkedHashMap<String, Object>();
        var args = new ArrayList<String>();
        boolean hide = false;
        for (String arg : HaloCliCatalog.arguments(input.get("arguments"))) {
            if (hide) {
                args.add("[已隐藏]");
                hide = false;
                continue;
            }
            if (arg.startsWith("-") && sensitive(arg.split("=", 2)[0])) {
                args.add(arg.contains("=") ? arg.substring(0, arg.indexOf('=') + 1) + "[已隐藏]" : arg);
                hide = !arg.contains("=");
            } else args.add(text(arg));
        }
        result.put("arguments", args);
        if (input.get("stdin") instanceof String stdin) {
            try {
                result.put("stdin", sanitize(JSON.readTree(stdin)));
            } catch (Exception error) {
                result.put("stdin", text(stdin));
            }
        }
        return result;
    }

    public static String pretty(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("CLI_OUTPUT_INVALID");
        }
    }
}
