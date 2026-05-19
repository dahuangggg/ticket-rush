package dev.dahuangggg.ticketrush.ai.service;

import dev.dahuangggg.ticketrush.ai.tools.ReminderTools;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderToolResult;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.RushReminderService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for the tool-call loop. Stubs the LangChain4j {@link ChatModel} so the test does
 * not touch OpenAI: the mock first returns an {@link AiMessage} carrying a {@link ToolExecutionRequest}
 * for {@code setRushReminder}, then on the follow-up call returns the final assistant text.
 *
 * Verifies that {@link AiServices} actually invokes our {@link ReminderTools} between the two model
 * calls and that the assistant's returned answer is the second model message.
 */
class AiAssistantServiceIT {

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void toolCallLoop_callsReminderToolAndReturnsAnswer() {
        // 1) Stub the chat model: first call -> tool request; second call -> final answer.
        ChatModel model = mock(ChatModel.class);

        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call-1")
                .name("setRushReminder")
                .arguments("{\"skuId\":42,\"leadMinutes\":5}")
                .build();

        ChatResponse firstResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(toolReq))
                .build();
        ChatResponse secondResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("已为你设置提醒"))
                .build();

        when(model.chat(any(ChatRequest.class))).thenReturn(firstResponse, secondResponse);

        // 2) Mock the reminder service the tool delegates to.
        RushReminderService reminderService = mock(RushReminderService.class);
        when(reminderService.setReminder(7L, 42L, 5))
                .thenReturn(ReminderToolResult.builder()
                        .ok(true)
                        .reminderId(1L)
                        .message("done")
                        .build());

        // 3) ReminderTools reads UserContext.
        UserContext.set(new LoginUser(7L, "13800000000", "user"));

        // 4) Build the assistant exactly the way AiConfig does (simplified: in-memory chat memory).
        ChatAssistant assistant = AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(new ReminderTools(reminderService))
                .build();

        // 5) Invoke and assert.
        String answer = assistant.chat("session-1", "提醒我开抢");

        assertThat(answer).contains("已为你设置提醒");
        verify(reminderService).setReminder(7L, 42L, 5);
    }

    @Test
    void stream_returnsPartialResponsesAndCompletes() throws Exception {
        ChatAssistant assistant = AiServices.builder(ChatAssistant.class)
                .streamingChatModel(new FakeStreamingChatModel())
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();

        List<String> chunks = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        assistant.stream("session-1", "你好")
                .onPartialResponse(chunks::add)
                .onCompleteResponse(response -> done.countDown())
                .onError(error -> done.countDown())
                .start();

        assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(chunks).containsExactly("你", "好");
    }

    static class FakeStreamingChatModel implements StreamingChatModel {
        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("你");
            handler.onPartialResponse("好");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("你好"))
                    .build());
        }
    }
}
