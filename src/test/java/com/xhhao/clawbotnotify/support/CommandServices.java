package com.xhhao.clawbotnotify.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xhhao.clawbotnotify.command.CommentReplyExecutor;
import com.xhhao.clawbotnotify.command.InboundCommandService;
import com.xhhao.clawbotnotify.command.InstanceIdentity;
import com.xhhao.clawbotnotify.command.QuotedCommentResolver;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.delivery.OutboundQueue;
import reactor.core.publisher.Mono;

public final class CommandServices {
    private CommandServices() {}

    public static com.xhhao.clawbotnotify.assistant.AssistantSettings disabledSettings() {
        var settings = mock(com.xhhao.clawbotnotify.assistant.AssistantSettings.class);
        when(settings.get()).thenReturn(Mono.just(new com.xhhao.clawbotnotify.assistant.AssistantSettings.Config()));
        return settings;
    }

    public static com.xhhao.clawbotnotify.media.WechatMediaInbox noMedia() {
        var inbox = mock(com.xhhao.clawbotnotify.media.WechatMediaInbox.class);
        when(inbox.receive(any(), any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
        return inbox;
    }

    public static InboundCommandService reads(ExtensionFixture fixture, CredentialStore store) {
        var executor = mock(CommentReplyExecutor.class);
        when(executor.process(any(), any())).thenAnswer(call -> Mono.just(call.getArgument(1)));
        return new InboundCommandService(
                fixture.client,
                new InstanceIdentity(fixture.client),
                new OutboundQueue(fixture.client),
                new QuotedCommentResolver(fixture.client),
                store,
                executor,
                org.mockito.Mockito.mock(com.xhhao.clawbotnotify.command.CommentActionExecutor.class),
                disabledSettings(),
                mock(com.xhhao.clawbotnotify.assistant.AiCommandService.class),
                mock(com.xhhao.clawbotnotify.assistant.draft.AssistantConfirmationExecutor.class),
                mock(com.xhhao.clawbotnotify.assistant.review.AssistantPreviewReader.class),
                com.xhhao.clawbotnotify.support.CommandServices.noMedia());
    }
}
