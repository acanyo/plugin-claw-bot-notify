package com.xhhao.clawbotnotify.command;

import com.xhhao.clawbotnotify.model.InboundCommand.Type;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Exact commands only; arbitrary messages and quoted comment bodies are never interpreted. */
public final class ReadCommandParser {
    private static final Pattern PENDING = Pattern.compile("待处理(?:\\s+([1-9][0-9]{0,5}))?");
    private static final Pattern VIEW = Pattern.compile("查看\\s+(C-[A-Z0-9]{4,12})", Pattern.CASE_INSENSITIVE);

    private ReadCommandParser() {}

    public record Parsed(Type type, int page, String code) {}

    public static Optional<Parsed> parse(String raw) {
        String text = raw.strip();
        if (text.equals("开始")) return of(Type.START, 1, null);
        if (text.equals("帮助")) return of(Type.HELP, 1, null);
        var pending = PENDING.matcher(text);
        if (pending.matches())
            return of(Type.PENDING, pending.group(1) == null ? 1 : Integer.parseInt(pending.group(1)), null);
        var view = VIEW.matcher(text);
        if (view.matches()) return of(Type.VIEW, 1, view.group(1).toUpperCase(Locale.ROOT));
        for (String command : new String[] {"开始", "帮助", "待处理", "查看", "回复", "审核并回复"}) {
            if (text.equals(command)
                    || text.startsWith(command + " ")
                    || text.startsWith(command + "\n")
                    || text.startsWith(command + "\t"))
                return of(command.contains("回复") ? Type.UNSUPPORTED : Type.INVALID, 1, null);
        }
        return Optional.empty();
    }

    private static Optional<Parsed> of(Type type, int page, String code) {
        return Optional.of(new Parsed(type, page, code));
    }
}
