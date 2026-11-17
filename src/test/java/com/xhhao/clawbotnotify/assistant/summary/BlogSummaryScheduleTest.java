package com.xhhao.clawbotnotify.assistant.summary;

import static org.assertj.core.api.Assertions.*;

import com.xhhao.clawbotnotify.assistant.AssistantSettings;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BlogSummaryScheduleTest {
    final AssistantSettings.Config config = new AssistantSettings.Config();

    @Test
    void weeklyDefaultUsesShanghaiSundayAndAdjacentNonOverlappingIntervals() {
        var before = BlogSummarySchedule.latest(config, Instant.parse("2026-11-22T12:59:59Z"));
        var due = BlogSummarySchedule.latest(config, Instant.parse("2026-11-22T13:00:00Z"));
        assertThat(before.end()).isEqualTo(Instant.parse("2026-11-15T13:00:00Z"));
        assertThat(due.start()).isEqualTo(before.end());
        assertThat(due.end()).isEqualTo(Instant.parse("2026-11-22T13:00:00Z"));
        assertThat(due.period()).contains("2026-11-15 21:00", "2026-11-22 21:00", "Asia/Shanghai");
        assertThat(BlogSummarySchedule.next(config, due.end())).isEqualTo(Instant.parse("2026-11-29T13:00:00Z"));
    }

    @Test
    void monthlyScheduleUsesActualLastDayIncludingLeapFebruary() {
        config.setBlogSummaryScheduleCycle("MONTHLY");
        var leap = BlogSummarySchedule.latest(config, Instant.parse("2028-02-29T13:00:00Z"));
        assertThat(leap.start()).isEqualTo(Instant.parse("2028-01-31T13:00:00Z"));
        assertThat(leap.end()).isEqualTo(Instant.parse("2028-02-29T13:00:00Z"));
        assertThat(BlogSummarySchedule.next(config, leap.end())).isEqualTo(Instant.parse("2028-03-31T13:00:00Z"));
        var regular = BlogSummarySchedule.latest(config, Instant.parse("2027-02-28T13:00:00Z"));
        assertThat(regular.end()).isEqualTo(Instant.parse("2027-02-28T13:00:00Z"));
    }

    @Test
    void dailyIntervalsAlwaysUseShanghaiNinePm() throws Exception {
        config.setBlogSummaryScheduleCycle("DAILY");
        var today = BlogSummarySchedule.latest(config, Instant.parse("2026-11-17T13:00:00Z"));
        assertThat(today.start()).isEqualTo(Instant.parse("2026-11-16T13:00:00Z"));
        assertThat(today.end()).isEqualTo(Instant.parse("2026-11-17T13:00:00Z"));
        assertThat(today.title()).isEqualTo("日报");
        assertThat(BlogSummarySchedule.next(config, today.end())).isEqualTo(Instant.parse("2026-11-18T13:00:00Z"));
        var old = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(
                        "{\"blogSummaryScheduleCycle\":\"DAILY\",\"blogSummaryScheduleTime\":\"05:30\",\"blogSummaryScheduleZone\":\"America/New_York\",\"blogSummaryScheduleWeekday\":2}",
                        AssistantSettings.Config.class);
        assertThat(BlogSummarySchedule.latest(old, today.end())).isEqualTo(today);
    }

    @Test
    void onlyTheThreeCyclesAreAccepted() {
        assertThat(BlogSummarySchedule.valid(config)).isTrue();
        for (String cycle : new String[] {"DAILY", "WEEKLY", "MONTHLY"}) {
            config.setBlogSummaryScheduleCycle(cycle);
            assertThat(BlogSummarySchedule.valid(config)).isTrue();
        }
        config.setBlogSummaryScheduleCycle("HOURLY");
        assertThat(BlogSummarySchedule.valid(config)).isFalse();
    }
}
