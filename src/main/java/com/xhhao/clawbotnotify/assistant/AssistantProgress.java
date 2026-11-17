package com.xhhao.clawbotnotify.assistant;

import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** One durable delayed notice; cancellation and completion cancel the timer with the main operation. */
@Component
public class AssistantProgress {
    public static final String TEXT = "已收到，正在处理。完成后会发送结果；涉及写操作时会先提供预览供您确认。";
    private final OutboundQueue queue;
    private final Supplier<Mono<Long>> timer;

    @Autowired
    public AssistantProgress(OutboundQueue queue) {
        this(queue, () -> Mono.delay(Duration.ofSeconds(8)));
    }

    AssistantProgress(OutboundQueue queue, Supplier<Mono<Long>> timer) {
        this.queue = queue;
        this.timer = timer;
    }

    public <T> Mono<T> watch(WechatBinding binding, InboundCommand command, Mono<T> action) {
        return Mono.firstWithSignal(
                action,
                Mono.defer(timer)
                        .then(Mono.defer(() -> queue.enqueue(
                                binding, "AI_PROGRESS", command.getMetadata().getName(), spec -> {
                                    spec.setCommandName(command.getMetadata().getName());
                                    spec.setQuotedMessageId(command.getSpec().getQuotedMessageId());
                                })))
                        .onErrorResume(error -> Mono.empty())
                        .then(Mono.<T>never()));
    }
}
