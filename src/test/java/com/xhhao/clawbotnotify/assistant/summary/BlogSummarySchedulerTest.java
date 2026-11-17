package com.xhhao.clawbotnotify.assistant.summary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.binding.BindingRepository;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.support.StableIds;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class BlogSummarySchedulerTest {
    final Instant activation = Instant.parse("2026-11-17T08:00:00Z");
    final Instant due = Instant.parse("2026-11-22T13:00:00Z");

    class Fixture {
        final AssistantFixture f = new AssistantFixture();
        final BindingRepository bindings = mock(BindingRepository.class);
        final ScheduledBlogSummary service = mock(ScheduledBlogSummary.class);
        final OutboundQueue queue = new OutboundQueue(f.resources.client);
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<InboundCommand> command = new AtomicReference<>();

        Fixture() throws Exception {
            f.config.setBlogSummaryScheduleEnabled(true);
            when(bindings.current()).thenAnswer(call -> Mono.just(f.binding));
            when(service.process(any(), any(), any())).thenAnswer(call -> {
                InboundCommand run = call.getArgument(1);
                command.set(run);
                return Mono.defer(() -> {
                    count.incrementAndGet();
                    return f.secrets
                            .update(run.getSpec().getBodyRef(), values -> {
                                values.put("aiResponse", "待确认的博客小结");
                                values.put("aiState", "AWAITING_APPROVAL");
                            })
                            .then(Mono.defer(() -> {
                                run.getSpec().setWriteState(InboundCommand.WriteState.AWAITING_APPROVAL);
                                return f.resources.client.update(run);
                            }));
                });
            });
        }

        BlogSummaryScheduler scheduler() {
            return new BlogSummaryScheduler(f.settings, bindings, f.secrets, f.resources.client, service, queue);
        }

        OutboundTask receipt() {
            return f.resources.get(
                    OutboundTask.class,
                    "out-"
                            + StableIds.hash(
                                    "primary",
                                    "3",
                                    "COMMAND",
                                    command.get().getMetadata().getName()));
        }
    }

    @Test
    void firstActivationDoesNotBackfillAndEachDueSlotIsGeneratedAndQueuedOnce() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        scheduler.tick(activation).block();
        assertThat(x.count).hasValue(0);
        scheduler.tick(due).block();
        scheduler.tick(due.plusSeconds(60)).block();
        x.scheduler().tick(due.plusSeconds(120)).block();
        assertThat(x.count).hasValue(1);
        assertThat(x.receipt().getSpec().getKind()).isEqualTo("COMMAND");
        assertThat(x.receipt().getSpec().getQuotedMessageId()).isNull();
        assertThat(x.receipt().getSpec().getCommandName()).startsWith("in-");
        assertThat(x.f.resources
                        .get(InboundCommand.class, x.command.get().getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(InboundCommand.State.QUEUED);
        verify(x.service, never()).recover(any());
    }

    @Test
    void restartAfterDowntimeOnlyGeneratesTheMostRecentSlot() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        scheduler.tick(activation).block();
        var captured = new AtomicReference<BlogSummarySchedule.Window>();
        doAnswer(call -> {
                    captured.set(call.getArgument(2));
                    return Mono.just(call.getArgument(1));
                })
                .when(x.service)
                .process(any(), any(), any());
        scheduler.tick(Instant.parse("2026-12-07T00:00:00Z")).block();
        assertThat(captured.get().end()).isEqualTo(Instant.parse("2026-12-06T13:00:00Z"));
        verify(x.service, times(1)).process(any(), any(), any());
    }

    @Test
    void disabledAssistantWritesScheduleAndBindingNeverCreateRuns() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        for (String disabled : new String[] {"assistant", "writes", "schedule", "binding"}) {
            x.f.config.setEnabled(!disabled.equals("assistant"));
            x.f.config.setAllowWrites(!disabled.equals("writes"));
            x.f.config.setBlogSummaryScheduleEnabled(!disabled.equals("schedule"));
            x.f.binding.getSpec().setEnabled(!disabled.equals("binding"));
            scheduler.tick(due).block();
        }
        verifyNoInteractions(x.service);
        assertThat(x.command).hasNullValue();
    }

    @Test
    void concurrentSchedulersDoNotStartASecondGenerationWhileTheFirstIsRunning() throws Exception {
        var x = new Fixture();
        var first = x.scheduler();
        var second = x.scheduler();
        first.tick(activation).block();
        Sinks.One<InboundCommand> completion = Sinks.one();
        doAnswer(call -> {
                    x.count.incrementAndGet();
                    x.command.set(call.getArgument(1));
                    return completion.asMono();
                })
                .when(x.service)
                .process(any(), any(), any());
        var subscription = first.tick(due).subscribe();
        second.tick(due).block();
        first.tick(due).block();
        assertThat(x.count).hasValue(1);
        verify(x.service, never()).recover(any());
        var done = x.command.get();
        done.getSpec().setWriteState(InboundCommand.WriteState.AWAITING_APPROVAL);
        completion.tryEmitValue(x.f.resources.put(done, false));
        assertThat(subscription.isDisposed()).isTrue();
        assertThat(x.receipt()).isNotNull();
    }

    @Test
    void interruptedRunIsRecoveredWithoutRepeatingModelAndQueuedOnce() throws Exception {
        var x = new Fixture();
        var first = x.scheduler();
        first.tick(activation).block();
        doAnswer(call -> {
                    var command = (InboundCommand) call.getArgument(1);
                    command.getSpec().setStartedAt(Instant.parse("2020-01-01T00:00:00Z"));
                    x.command.set(x.f.resources.put(command, false));
                    return Mono.never();
                })
                .when(x.service)
                .process(any(), any(), any());
        var active = first.tick(due).subscribe();
        active.dispose();
        when(x.service.recover(any())).thenAnswer(call -> {
            InboundCommand command = call.getArgument(0);
            command.getSpec().setWriteState(InboundCommand.WriteState.REJECTED);
            return x.f.secrets
                    .update(command.getSpec().getBodyRef(), values -> values.put("aiResponse", "运行被中断"))
                    .then(x.f.resources.client.update(command));
        });
        x.scheduler().tick(due.plusSeconds(60)).block();
        verify(x.service, times(1)).process(any(), any(), any());
        verify(x.service, times(1)).recover(any());
        assertThat(x.receipt()).isNotNull();
    }

    @Test
    void queueStorageFailureIsRepairedWithoutGeneratingAgain() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        scheduler.tick(activation).block();
        x.f.resources.failCreatesFor = OutboundTask.class;
        assertThatThrownBy(() -> scheduler.tick(due).block()).hasMessage("SIMULATED_CREATE_FAILURE");
        assertThat(x.count).hasValue(1);
        x.f.resources.failCreatesFor = null;
        scheduler.tick(due.plusSeconds(60)).block();
        assertThat(x.count).hasValue(1);
        assertThat(x.receipt()).isNotNull();
        verify(x.service, never()).recover(any());
    }

    @Test
    void rebindStartsANewScheduleWithoutUsingThePreviousOwnersRun() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        scheduler.tick(activation).block();
        scheduler.tick(due).block();
        assertThat(x.count).hasValue(1);
        x.f.binding.getSpec().setGeneration(4);
        x.f.binding.getSpec().setOwnerWechatUserId("new-owner");
        scheduler.tick(due.plusSeconds(60)).block();
        assertThat(x.count).hasValue(1);
        scheduler.tick(Instant.parse("2026-11-29T13:00:00Z")).block();
        assertThat(x.count).hasValue(2);
        assertThat(x.command.get().getSpec().getGeneration()).isEqualTo(4);
    }

    @Test
    void emptyPeriodIsRecordedWithoutSendingAnEmptySummary() throws Exception {
        var x = new Fixture();
        var scheduler = x.scheduler();
        scheduler.tick(activation).block();
        doAnswer(call -> {
                    InboundCommand command = call.getArgument(1);
                    x.command.set(command);
                    return x.f.secrets
                            .update(command.getSpec().getBodyRef(), values -> values.put("scheduledEmpty", "true"))
                            .then(Mono.defer(() -> {
                                command.getSpec().setWriteState(InboundCommand.WriteState.SUCCEEDED);
                                return x.f.resources.client.update(command);
                            }));
                })
                .when(x.service)
                .process(any(), any(), any());
        scheduler.tick(due).block();
        assertThat(x.receipt()).isNull();
        assertThat(x.f.resources
                        .get(InboundCommand.class, x.command.get().getMetadata().getName())
                        .getSpec()
                        .getState())
                .isEqualTo(InboundCommand.State.QUEUED);
    }
}
