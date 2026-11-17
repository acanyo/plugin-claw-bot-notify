package com.xhhao.clawbotnotify.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xhhao.clawbotnotify.assistant.summary.BlogSummarySchedule;
import lombok.Data;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

@Component
public class AssistantSettings {
    private final ReactiveSettingFetcher settings;

    public AssistantSettings(ReactiveSettingFetcher settings) {
        this.settings = settings;
    }

    public Mono<Config> get() {
        return settings.fetch("assistant", Config.class)
                .defaultIfEmpty(new Config())
                .map(config -> {
                    if (config.maxContextChars < 1
                            || config.draftMinutes < 5
                            || config.draftMinutes > 1440
                            || config.retentionDays < 1
                            || config.retentionDays > 365
                            || !AssistantPreferences.valid(config)
                            || !BlogSummarySchedule.valid(config))
                        throw new IllegalStateException("AI_SETTINGS_INVALID");
                    return config;
                })
                .onErrorMap(error -> new IllegalStateException("AI_SETTINGS_INVALID"));
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Config {
        private boolean enabled;
        private String modelName;
        private boolean allowWrites = true;
        private String momentPublishUser;
        private String preferredName = "老板";
        private String blogSummaryTemplate = AssistantPreferences.DEFAULT_SUMMARY_TEMPLATE;
        private String blogSummaryVisible = "PUBLIC";
        private boolean blogSummaryIncludeLinks = true;
        private boolean blogSummaryScheduleEnabled;
        private String blogSummaryScheduleCycle = "WEEKLY";
        private int maxContextChars = 120000;
        private int draftMinutes = 60;
        private int retentionDays = 30;
    }
}
