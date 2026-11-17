package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhhao.clawbotnotify.media.HaloMediaClient;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class BotMomentPublisherTest {
    final ObjectMapper json = new ObjectMapper();
    final ExtensionFixture resources = new ExtensionFixture();
    final HaloMediaClient halo = mock(HaloMediaClient.class);
    final BotMomentPublisher publisher = new BotMomentPublisher(new MomentAuthors(resources.client), halo);
    final MomentAuthors.Author author = new MomentAuthors.Author("chosen-bot", "自选机器人");

    BotMomentPublisherTest() {
        var user = new run.halo.app.core.extension.User();
        var metadata = new run.halo.app.extension.Metadata();
        metadata.setName(author.username());
        user.setMetadata(metadata);
        user.setSpec(new run.halo.app.core.extension.User.UserSpec());
        user.getSpec().setDisplayName(author.displayName());
        resources.put(user, true);
    }

    @Test
    void cliPublicationPreservesAllOptionsAndAlwaysUsesTheBotAuthor() {
        when(halo.momentResource(eq("private-pat"), any()))
                .thenAnswer(call -> Mono.just(json.valueToTree(call.getArgument(1))));
        var result = publisher
                .publishCli(
                        "private-pat",
                        "command",
                        List.of(
                                "moment",
                                "create",
                                "--content",
                                "<p>精确正文</p>",
                                "--name=moment-one",
                                "--visible=PRIVATE",
                                "--tags=周报, AI,周报",
                                "--release-time",
                                "2026-11-17T21:00:00+08:00",
                                "--approved=false",
                                "--profile=clawbot",
                                "--json"),
                        author)
                .block();
        var spec = result.path("spec");
        assertThat(spec.path("owner").asText()).isEqualTo("chosen-bot");
        assertThat(spec.path("content").path("html").asText()).isEqualTo("<p>精确正文</p>");
        assertThat(spec.path("content").path("raw").asText()).isEqualTo("<p>精确正文</p>");
        assertThat(spec.path("visible").asText()).isEqualTo("PRIVATE");
        assertThat(spec.path("releaseTime").asText()).isEqualTo("2026-11-17T13:00:00Z");
        assertThat(spec.path("approved").asBoolean()).isFalse();
        assertThat(spec.path("tags").toString()).isEqualTo("[\"周报\",\"AI\"]");
        verify(halo).momentResource(eq("private-pat"), any());
    }

    @Test
    void anUnexpectedAuthorCannotBeReportedAsSuccess() throws Exception {
        when(halo.momentResource(anyString(), any()))
                .thenReturn(Mono.just(
                        json.readTree("{\"metadata\":{\"name\":\"moment\"},\"spec\":{\"owner\":\"admin\"}}")));
        assertThatThrownBy(() -> publisher
                        .publishCli(
                                "pat", "command", List.of("moment", "create", "--name=moment", "--content=正文"), author)
                        .block())
                .hasMessage("HALO_BOT_AUTHOR_UNCONFIRMED");
        verify(halo, times(1)).momentResource(anyString(), any());
    }

    @Test
    void anUnknownCliOptionFailsBeforePublication() {
        assertThatThrownBy(() -> publisher
                        .publishCli(
                                "pat",
                                "command",
                                List.of("moment", "create", "--content=正文", "--future-owner-option=admin"),
                                author)
                        .block())
                .hasMessage("CLI_CONTRACT_CHANGED");
        verifyNoInteractions(halo);
        assertThat(resources.updates).hasValue(1);
    }

    @Test
    void aLegacyPreviewWithoutAnAuthorNeverFallsBackToTheLoginAccount() {
        assertThatThrownBy(() -> publisher
                        .publishCli("pat", "command", List.of("moment", "create", "--content=正文"), null)
                        .block())
                .hasMessage("HALO_MOMENT_AUTHOR_REQUIRED");
        verifyNoInteractions(halo);
    }
}
