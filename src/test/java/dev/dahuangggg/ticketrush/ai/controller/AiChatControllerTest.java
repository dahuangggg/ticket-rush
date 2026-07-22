package dev.dahuangggg.ticketrush.ai.controller;

import dev.dahuangggg.ticketrush.ai.dto.ChatRequest;
import dev.dahuangggg.ticketrush.ai.service.AiAssistantService;
import dev.dahuangggg.ticketrush.exception.AiServiceUnavailableException;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiChatControllerTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void synchronousFailureUsesStableServiceUnavailableException() {
        AiAssistantService service = mock(AiAssistantService.class);
        when(service.chat(7L, "session-1", "你好"))
                .thenThrow(new IllegalStateException("internal-provider-name"));
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        assertThatThrownBy(() -> new AiChatController(service)
                .chat(new ChatRequest("session-1", "你好")))
                .isInstanceOf(AiServiceUnavailableException.class)
                .hasMessage("AI 服务暂时不可用")
                .hasRootCauseMessage("internal-provider-name");
    }

    @Test
    void streamHasFiniteTimeoutAndClearsRequestThreadContextBeforeReturning() {
        AiAssistantService service = mock(AiAssistantService.class);
        TokenStream stream = mock(TokenStream.class);
        when(service.stream(7L, "session-1", "你好")).thenReturn(stream);
        when(stream.beforeToolExecution(any())).thenReturn(stream);
        when(stream.onToolExecuted(any())).thenReturn(stream);
        when(stream.onPartialResponse(any())).thenReturn(stream);
        when(stream.onCompleteResponse(any())).thenReturn(stream);
        when(stream.onError(any())).thenReturn(stream);
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        var emitter = new AiChatController(service)
                .chatStream(new ChatRequest("session-1", "你好"));

        assertThat(emitter.getTimeout()).isEqualTo(60_000L);
        assertThat(UserContext.get()).isNull();
        verify(stream).start();
    }
}
