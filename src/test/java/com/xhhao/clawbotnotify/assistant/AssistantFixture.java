package com.xhhao.clawbotnotify.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.assistant.cli.HaloCliProcess;
import com.xhhao.clawbotnotify.assistant.draft.AssistantConfirmationExecutor;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDraftContext;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.command.CommentReplyExecutor;
import com.xhhao.clawbotnotify.comment.CommentLinkRepository;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.SecretCredentialStore;
import com.xhhao.clawbotnotify.halo.CommentTargetReader;
import com.xhhao.clawbotnotify.halo.HaloCommentReader;
import com.xhhao.clawbotnotify.halo.HaloIdentityVerifier;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.infra.ExternalUrlSupplier;

public final class AssistantFixture {
    public final ExtensionFixture resources = new ExtensionFixture();
    public final SecretCredentialStore secrets = new SecretCredentialStore(
            resources.client, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
    public final WechatBinding binding = new WechatBinding();
    public final AssistantAuthority authority = mock(AssistantAuthority.class);
    public final AssistantSettings settings = mock(AssistantSettings.class);
    public final AssistantSettings.Config config = new AssistantSettings.Config();
    public final HaloCliProcess cli = mock(HaloCliProcess.class);
    public final AiFoundationGateway model = mock(AiFoundationGateway.class);
    public final AssistantConversations history = new AssistantConversations(secrets);
    public final com.xhhao.clawbotnotify.halo.MomentAuthors authors =
            new com.xhhao.clawbotnotify.halo.MomentAuthors(resources.client);
    public final AssistantDrafts drafts = new AssistantDrafts(resources.client, secrets, settings, authors);
    public final HaloCommentReader comments = mock(HaloCommentReader.class);
    public final HaloIdentityVerifier halo = mock(HaloIdentityVerifier.class);
    public final CommentReplyExecutor replies = new CommentReplyExecutor(
            resources.client, secrets, comments, halo, new CommentLinkRepository(resources.client));
    public final AssistantConfirmationExecutor confirmations;
    public final AssistantTools tools;
    public final com.xhhao.clawbotnotify.halo.BotMomentPublisher moments =
            mock(com.xhhao.clawbotnotify.halo.BotMomentPublisher.class);
    public final com.xhhao.clawbotnotify.media.AssistantMediaTools mediaTools =
            mock(com.xhhao.clawbotnotify.media.AssistantMediaTools.class);
    public final AiCommandService service;

    public AssistantFixture() throws Exception {
        var spec = new WechatBinding.Spec();
        spec.setGeneration(3);
        spec.setBotId("bot");
        spec.setOwnerWechatUserId("owner");
        spec.setHaloUsername("admin");
        spec.setCredentialRef("fixture-credentials");
        secrets.create("fixture-credentials", Map.of("haloPat", "private-pat")).block();
        spec.setEnabled(true);
        binding.setSpec(spec);
        var user = new run.halo.app.core.extension.User();
        var userMetadata = new Metadata();
        userMetadata.setName("clawbot");
        user.setMetadata(userMetadata);
        user.setSpec(new run.halo.app.core.extension.User.UserSpec());
        user.getSpec().setDisplayName("拾光 Bot");
        user.getSpec().setDisabled(true);
        resources.put(user, true);
        config.setMomentPublishUser("clawbot");
        config.setEnabled(true);
        when(settings.get()).thenReturn(Mono.just(config));
        when(authority.check(any())).thenReturn(Mono.just(binding));
        var url = mock(ExternalUrlSupplier.class);
        when(url.getRaw()).thenReturn(URI.create("http://localhost:18091").toURL());
        tools = new AssistantTools(cli, url, authority, settings, secrets, drafts, mediaTools, moments);
        service = new AiCommandService(
                resources.client,
                secrets,
                authority,
                settings,
                history,
                new AssistantGenerationRunner(model),
                tools,
                drafts,
                new AssistantDraftContext(secrets, new CommentTargetReader(comments), drafts, tools),
                new AssistantProgress(new com.xhhao.clawbotnotify.delivery.OutboundQueue(resources.client)));
        confirmations = new AssistantConfirmationExecutor(
                resources.client, secrets, drafts, authority, settings, tools, replies);
    }

    public Mono<Object> confirmed(InboundCommand command, String tool, Map<String, Object> input) {
        Mono<AssistantDrafts.Draft> proposal = com.xhhao.clawbotnotify.halo.MomentAuthors.publication(tool, input)
                ? authors.resolve(config.getMomentPublishUser())
                        .map(author -> new AssistantDrafts.Draft(tool, input, null, false, author))
                : Mono.just(new AssistantDrafts.Draft(tool, input, null, false));
        return proposal.flatMap(draft -> secrets.update(
                        command.getSpec().getBodyRef(),
                        values -> values.put("confirmedProposal", drafts.encode(draft))))
                .then(tools.executeConfirmed(binding, command));
    }

    public InboundCommand command(String name, String body) {
        var command = new InboundCommand();
        var metadata = new Metadata();
        metadata.setName(name);
        command.setMetadata(metadata);
        var spec = new InboundCommand.Spec();
        spec.setType(InboundCommand.Type.AI);
        spec.setGeneration(3);
        spec.setBodyRef("body-" + name);
        spec.setWriteState(InboundCommand.WriteState.READY);
        spec.setReceivedAt(Instant.now());
        command.setSpec(spec);
        secrets.create(spec.getBodyRef(), Map.of("body", body)).block();
        return resources.put(command, true);
    }
}
