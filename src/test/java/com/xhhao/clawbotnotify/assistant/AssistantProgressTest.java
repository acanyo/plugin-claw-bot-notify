package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;

import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.OutboundTask;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;

class AssistantProgressTest {
    @Test
    void fastCompletionAndCancellationNeverQueueProgress() throws Exception {
        var f = new AssistantFixture();
        var timerCancelled = new AtomicBoolean();
        var timer = Sinks.<Long>one();
        var progress = new AssistantProgress(
                new OutboundQueue(f.resources.client), () -> timer.asMono().doOnCancel(() -> timerCancelled.set(true)));
        assertThat(progress.watch(f.binding, f.command("fast", "你好"), Mono.just("答复"))
                        .block())
                .isEqualTo("答复");
        var running = progress.watch(f.binding, f.command("cancel", "测试"), Mono.never())
                .subscribe();
        running.dispose();
        assertThat(timerCancelled).isTrue();
        timer.tryEmitValue(1L);
        assertThat(f.resources
                        .client
                        .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(20))
                        .block()
                        .getTotal())
                .isZero();
    }

    @Test
    void slowOperationQueuesOnceAndKeepsWaitingForTheRealResult() throws Exception {
        var f = new AssistantFixture();
        var timer = Sinks.<Long>one();
        var answer = Sinks.<String>one();
        var progress = new AssistantProgress(new OutboundQueue(f.resources.client), timer::asMono);
        var command = f.command("slow", "周报");
        var future = progress.watch(f.binding, command, answer.asMono()).toFuture();
        timer.tryEmitValue(1L);
        assertThat(future).isNotDone();
        var rows = f.resources
                .client
                .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(20))
                .block();
        assertThat(rows.getTotal()).isEqualTo(1);
        assertThat(rows.getItems().getFirst().getSpec().getKind()).isEqualTo("AI_PROGRESS");
        assertThat(rows.getItems().getFirst().getSpec().getCommandName()).isEqualTo("slow");
        answer.tryEmitValue("最终周报");
        assertThat(future.get()).isEqualTo("最终周报");
        progress.watch(f.binding, command, Mono.never()).subscribe().dispose();
        assertThat(f.resources
                        .client
                        .listBy(OutboundTask.class, new ListOptions(), PageRequestImpl.ofSize(20))
                        .block()
                        .getTotal())
                .isEqualTo(1);
    }

    @Test
    void AProgressStorageFailureCannotFailOrRepeatTheMainOperation() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("storage-failure", "周报");
        f.resources.failCreatesFor = OutboundTask.class;
        var timer = Sinks.<Long>one();
        var answer = Sinks.<String>one();
        var progress = new AssistantProgress(new OutboundQueue(f.resources.client), timer::asMono);
        var future = progress.watch(f.binding, command, answer.asMono()).toFuture();
        timer.tryEmitValue(1L);
        assertThat(future).isNotDone();
        answer.tryEmitValue("最终答复");
        assertThat(future.get()).isEqualTo("最终答复");
    }
}
