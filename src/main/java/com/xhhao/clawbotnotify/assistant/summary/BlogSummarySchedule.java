package com.xhhao.clawbotnotify.assistant.summary;

import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/** Calendar schedules use local dates; every summary covers the full interval between two planned runs. */
public final class BlogSummarySchedule {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime TIME = LocalTime.of(21, 0);

    private BlogSummarySchedule() {}

    public record Window(Instant start, Instant end, String period, String title) {}

    public static boolean valid(AssistantSettings.Config config) {
        return config.getBlogSummaryScheduleCycle() != null
                && Set.of("DAILY", "WEEKLY", "MONTHLY").contains(config.getBlogSummaryScheduleCycle());
    }

    public static Window latest(AssistantSettings.Config config, Instant now) {
        var zone = ZONE;
        var time = TIME;
        LocalDate date = now.atZone(zone).toLocalDate();
        date = switch (config.getBlogSummaryScheduleCycle()) {
            case "DAILY" -> date;
            case "WEEKLY" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            default -> date.with(TemporalAdjusters.lastDayOfMonth());
        };
        ZonedDateTime end = date.atTime(time).atZone(zone);
        if (end.toInstant().isAfter(now)) end = previous(config, end);
        var start = previous(config, end);
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx");
        String title =
                switch (config.getBlogSummaryScheduleCycle()) {
                    case "DAILY" -> "日报";
                    case "WEEKLY" -> "周报";
                    default -> "月报";
                };
        return new Window(
                start.toInstant(),
                end.toInstant(),
                formatter.format(start) + " 至 " + formatter.format(end) + "（" + zone + "，起含止不含）",
                title);
    }

    public static Instant next(AssistantSettings.Config config, Instant now) {
        var latest = latest(config, now);
        var current = latest.end().atZone(ZONE);
        return switch (config.getBlogSummaryScheduleCycle()) {
            case "DAILY" ->
                current.toLocalDate()
                        .plusDays(1)
                        .atTime(TIME)
                        .atZone(current.getZone())
                        .toInstant();
            case "WEEKLY" ->
                current.toLocalDate()
                        .plusWeeks(1)
                        .atTime(TIME)
                        .atZone(current.getZone())
                        .toInstant();
            default ->
                current.toLocalDate()
                        .plusMonths(1)
                        .with(TemporalAdjusters.lastDayOfMonth())
                        .atTime(TIME)
                        .atZone(current.getZone())
                        .toInstant();
        };
    }

    private static ZonedDateTime previous(AssistantSettings.Config config, ZonedDateTime current) {
        LocalDate date =
                switch (config.getBlogSummaryScheduleCycle()) {
                    case "DAILY" -> current.toLocalDate().minusDays(1);
                    case "WEEKLY" -> current.toLocalDate().minusWeeks(1);
                    default -> current.toLocalDate().minusMonths(1).with(TemporalAdjusters.lastDayOfMonth());
                };
        return date.atTime(TIME).atZone(current.getZone());
    }

    public static String identity(AssistantSettings.Config config) {
        return config.getBlogSummaryScheduleCycle() + ":21:00:7:Asia/Shanghai";
    }
}
