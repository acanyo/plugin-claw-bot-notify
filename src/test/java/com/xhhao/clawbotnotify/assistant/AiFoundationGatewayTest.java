package com.xhhao.clawbotnotify.assistant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.aifoundation.AiModelService;
import run.halo.aifoundation.chat.*;
import run.halo.aifoundation.message.ModelMessage;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

class AiFoundationGatewayTest {
    @Test
    void resolvesModelThroughEnabledExtensionAndUsesSdkRequest() {
        var extensions = mock(ExtensionGetter.class);
        var service = mock(AiModelService.class);
        var model = mock(LanguageModel.class);
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel("chosen")).thenReturn(Mono.just(model));
        when(model.capabilities()).thenReturn(LanguageModelCapabilities.defaults());
        when(model.generateText(any(GenerateTextRequest.class)))
                .thenReturn(Mono.just(GenerateTextResult.builder().text("ok").build()));
        var request = GenerateTextRequest.builder()
                .messages(List.of(ModelMessage.user("hello")))
                .maxRetries(0)
                .build();
        assertThat(new AiFoundationGateway(extensions)
                        .generate("chosen", request)
                        .block()
                        .getText())
                .isEqualTo("ok");
        verify(model).generateText(request);
    }

    @Test
    void missingFoundationOrModelIsAnExplicitFailure() {
        var extensions = mock(ExtensionGetter.class);
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.empty());
        var gateway = new AiFoundationGateway(extensions);
        assertThatThrownBy(
                        () -> gateway.generate(null, new GenerateTextRequest()).block())
                .hasMessage("AI_FOUNDATION_UNAVAILABLE");
        var service = mock(AiModelService.class);
        when(extensions.getEnabledExtension(AiModelService.class)).thenReturn(Mono.just(service));
        when(service.languageModel((String) null)).thenReturn(Mono.empty());
        assertThatThrownBy(
                        () -> gateway.generate(null, new GenerateTextRequest()).block())
                .hasMessage("AI_MODEL_UNAVAILABLE");
    }
}
