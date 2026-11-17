package com.xhhao.clawbotnotify.assistant.draft;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.assistant.AssistantFixture;
import com.xhhao.clawbotnotify.halo.MomentAuthors;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class MomentAuthorSelectionTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void existingDraftsWithoutAuthorMetadataKeepTheirSerializedVersion() throws Exception {
        var f = new AssistantFixture();
        String legacy =
                "{\"tool\":\"post_create_draft\",\"input\":{\"content\":\"正文\"},\"fingerprint\":null,\"approval\":false}";
        assertThat(json.readTree(f.drafts.encode(f.drafts.decode(legacy)))).isEqualTo(json.readTree(legacy));
    }

    @Test
    void everyMomentPreviewFreezesTheSelectedAuthorAndItsDisplayedIdentity() throws Exception {
        var f = new AssistantFixture();
        var user = f.resources.get(run.halo.app.core.extension.User.class, "clawbot");
        user.getMetadata().setName("another-user");
        user.getMetadata().setVersion(null);
        user.getSpec().setDisplayName("自选作者");
        f.resources.put(user, true);
        f.config.setMomentPublishUser("another-user");
        for (String tool : List.of("moment_create", "moment_create_images", "halo_cli")) {
            var command = f.command("preview-" + tool, "发布瞬间");
            var input =
                    switch (tool) {
                        case "halo_cli" ->
                            Map.<String, Object>of("arguments", List.of("moment", "create", "--content=正文"));
                        case "moment_create_images" -> Map.<String, Object>of("content", "正文", "images", List.of());
                        default -> Map.<String, Object>of("content", "正文");
                    };
            f.drafts.stage(command, tool, input, null, false).block();
            var draft =
                    f.drafts.load(f.binding, command.getMetadata().getName()).block();
            assertThat(draft.author()).isEqualTo(new MomentAuthors.Author("another-user", "自选作者"));
            assertThat(f.drafts.preview(draft)).contains("发布身份：自选作者（another-user）");
        }
        verifyNoInteractions(f.moments, f.cli, f.model);
    }

    @Test
    void changingTheSelectionAfterPreviewDoesNotRetargetTheConfirmedPublication() throws Exception {
        var f = new AssistantFixture();
        var command = f.command("frozen-author", "正文");
        f.drafts
                .stage(command, "moment_create", Map.of("content", "正文"), null, false)
                .block();
        var draft = f.drafts.load(f.binding, command.getMetadata().getName()).block();
        f.config.setMomentPublishUser("a-different-user");
        f.secrets
                .update(
                        command.getSpec().getBodyRef(),
                        values -> values.put("confirmedProposal", f.drafts.encode(draft)))
                .block();
        when(f.cli.run(List.of("auth", "current")))
                .thenReturn(Mono.just(json.readTree("{\"baseUrl\":\"http://localhost:18091\"}")));
        when(f.moments.publishCli(anyString(), anyString(), anyList(), any())).thenAnswer(call -> {
            List<String> arguments = call.getArgument(2);
            String name = arguments.stream()
                    .filter(a -> a.startsWith("--name="))
                    .findFirst()
                    .orElseThrow()
                    .substring(7);
            var response = json.createObjectNode();
            response.putObject("metadata").put("name", name);
            response.putObject("spec").put("owner", ((MomentAuthors.Author) call.getArgument(3)).username());
            return Mono.just(response);
        });
        f.tools.executeConfirmed(f.binding, command).block();
        verify(f.moments)
                .publishCli(
                        eq("private-pat"), anyString(), anyList(), eq(new MomentAuthors.Author("clawbot", "拾光 Bot")));
        assertThat(f.drafts.preview(draft)).contains("拾光 Bot（clawbot）").doesNotContain("a-different-user");
    }

    @Test
    void anUnselectedAuthorBlocksPreviewWithoutAffectingNonMomentDrafts() throws Exception {
        var f = new AssistantFixture();
        f.config.setMomentPublishUser(null);
        var command = f.command("no-author", "正文");
        assertThatThrownBy(() -> f.drafts
                        .stage(command, "moment_create", Map.of("content", "正文"), null, false)
                        .block())
                .hasMessage("HALO_MOMENT_AUTHOR_REQUIRED");
        assertThat(f.secrets.read(command.getSpec().getBodyRef()).block()).doesNotContainKey("proposal");
        f.drafts
                .stage(command, "post_create_draft", Map.of("content", "正文", "title", "草稿"), null, false)
                .block();
        verifyNoInteractions(f.moments, f.cli, f.model);
    }

    @Test
    void deletingThePreviewAuthorRejectsConfirmationBeforeClaimingAnyWrite() throws Exception {
        var f = new AssistantFixture();
        var source = f.command("deleted-author-preview", "正文");
        f.drafts
                .stage(source, "moment_create", Map.of("content", "正文"), null, false)
                .block();
        f.resources
                .client
                .delete(f.resources.get(run.halo.app.core.extension.User.class, "clawbot"))
                .block();
        var confirmation = f.command("deleted-author-confirm", "确认发布");
        confirmation.getSpec().setType(com.xhhao.clawbotnotify.model.InboundCommand.Type.AI_CONFIRM);
        confirmation.getSpec().setPreviewOf(source.getMetadata().getName());
        var result = f.confirmations.process(f.binding, confirmation).block();
        assertThat(result.getSpec().getWriteState())
                .isEqualTo(com.xhhao.clawbotnotify.model.InboundCommand.WriteState.REJECTED);
        assertThat(result.getSpec().getErrorCode()).isEqualTo("HALO_MOMENT_AUTHOR_UNAVAILABLE");
        assertThat(f.secrets.read(confirmation.getSpec().getBodyRef()).block()).doesNotContainKey("mutation");
        verifyNoInteractions(f.moments, f.cli, f.model);
    }
}
