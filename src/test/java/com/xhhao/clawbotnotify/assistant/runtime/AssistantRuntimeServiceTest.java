package com.xhhao.clawbotnotify.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AssistantRuntimeServiceTest {
    private static com.xhhao.clawbotnotify.media.WechatMediaInbox media() {
        var media = org.mockito.Mockito.mock(com.xhhao.clawbotnotify.media.WechatMediaInbox.class);
        org.mockito.Mockito.when(media.cleanup()).thenReturn(reactor.core.publisher.Mono.empty());
        return media;
    }

    @Test
    void statusChecksAvailabilityWithoutGeneratingAndSanitizesFailureMessages() throws Exception {
        var f = new AssistantFixture();
        when(f.model.available(any())).thenReturn(Mono.error(new IllegalStateException("secret-provider-password")));
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.cli.run(List.of("post", "list", "--page=1", "--size=1")))
                .thenReturn(Mono.just(new ObjectMapper().readTree("{\"items\":[]}")));
        var cleaner = new AssistantMaintenance(f.resources.client, f.secrets, f.settings, f.history, media());
        var runtime = new AssistantRuntimeService(f.settings, f.model, f.tools, cleaner);
        var result = runtime.status().block();
        assertThat(result.cli().available()).isTrue();
        assertThat(result.model().available()).isFalse();
        assertThat(result.model().errorCode()).isEqualTo("AI_REQUEST_FAILED");
        assertThat(result.model().latencyMs()).isNotNegative();
        assertThat(result.retentionDays()).isEqualTo(30);
        assertThat(result.toString()).doesNotContain("secret-provider-password");
        verify(f.model, never()).generate(any(), any());
    }
}
