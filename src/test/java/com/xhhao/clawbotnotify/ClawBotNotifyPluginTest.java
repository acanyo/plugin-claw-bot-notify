package com.xhhao.clawbotnotify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.binding.BindingService;
import com.xhhao.clawbotnotify.model.OutboundTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.exception.SchemeNotFoundException;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class ClawBotNotifyPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemes;

    @Mock
    BindingService bindings;

    @Mock
    com.xhhao.clawbotnotify.assistant.runtime.AssistantMaintenance maintenance;

    @Mock
    com.xhhao.clawbotnotify.assistant.summary.BlogSummaryScheduler summaryScheduler;

    @InjectMocks
    ClawBotNotifyPlugin plugin;

    @Test
    void contextLoads() {
        when(bindings.start()).thenReturn(Mono.empty());
        when(bindings.stop()).thenReturn(Mono.empty());
        plugin.start();
        plugin.stop();
        verify(bindings).start();
        verify(bindings).stop();
        verify(summaryScheduler).start();
        verify(summaryScheduler).stop();
        verify(schemes, times(4)).unregister(any());
    }

    @Test
    void shutdownToleratesSchemesAlreadyRemovedByHalo() {
        when(bindings.stop())
                .thenThrow(new SchemeNotFoundException(
                        Scheme.buildFromType(OutboundTask.class).groupVersionKind()));
        assertThatCode(plugin::stop).doesNotThrowAnyException();
        verify(bindings).stop();
        verify(schemes, times(4)).unregister(any());
    }
}
