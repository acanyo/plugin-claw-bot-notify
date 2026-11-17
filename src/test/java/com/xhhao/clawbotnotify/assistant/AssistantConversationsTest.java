package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.Secret;

class AssistantConversationsTest {
    @Test
    void historyIsEncryptedPrunedByWholeTurnsAndIsolatedAcrossBindings() throws Exception {
        var f = new AssistantFixture();
        f.config.setMaxContextChars((int) AssistantContextBudget.size(List.of(
                new AssistantConversations.Turn(
                        "cmd1", List.of(ModelMessage.user("privatebody1"), ModelMessage.assistant("answer1"))),
                new AssistantConversations.Turn(
                        "cmd2", List.of(ModelMessage.user("privatebody2"), ModelMessage.assistant("answer2"))))));
        f.history.messages(f.binding, f.config).block();
        for (int i = 0; i < 3; ++i)
            f.history
                    .append(
                            f.binding,
                            f.config,
                            "cmd" + i,
                            "privatebody" + i,
                            List.of(ModelMessage.assistant("answer" + i)))
                    .block();
        var history = f.history.messages(f.binding, f.config).block();
        assertThat(history).hasSize(4);
        assertThat(history.getFirst().getContent().getFirst().getText()).isEqualTo("privatebody1");
        var rows = f.resources
                .client
                .listBy(Secret.class, new ListOptions(), PageRequestImpl.ofSize(100))
                .block();
        for (var secret : rows.getItems())
            assertThat(new String(secret.getData().get("encrypted-v1"), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("privatebody");
        f.binding.getSpec().setGeneration(4);
        assertThat(f.history.messages(f.binding, f.config).block()).isEmpty();
    }

    @Test
    void idleHistoryIsRetainedAndAppendIsIdempotent() throws Exception {
        var f = new AssistantFixture();
        f.history.messages(f.binding, f.config).block();
        for (int i = 0; i < 2; ++i)
            f.history
                    .append(f.binding, f.config, "same", "question", List.of(ModelMessage.assistant("answer")))
                    .block();
        assertThat(f.history.messages(f.binding, f.config).block()).hasSize(2);
        var secret = f.resources
                .client
                .listBy(Secret.class, new ListOptions(), PageRequestImpl.ofSize(100))
                .block()
                .getItems()
                .getFirst();
        f.secrets
                .update(
                        secret.getMetadata().getName(),
                        data -> data.put(
                                "updatedAt",
                                java.time.Instant.now().minusSeconds(4000).toString()))
                .block();
        assertThat(f.history.messages(f.binding, f.config).block()).hasSize(2);
    }
}
