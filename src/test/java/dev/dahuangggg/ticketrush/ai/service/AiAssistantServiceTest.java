package dev.dahuangggg.ticketrush.ai.service;

import dev.dahuangggg.ticketrush.ai.tools.EventQueryTools;
import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;
import dev.dahuangggg.ticketrush.dto.event.EventDTO;
import dev.dahuangggg.ticketrush.dto.event.EventListRequest;
import dev.dahuangggg.ticketrush.dto.sku.TicketSkuDTO;
import dev.dahuangggg.ticketrush.service.EventService;
import dev.dahuangggg.ticketrush.service.TicketSkuService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 Fake 模型验证 LangChain4j tool-call 循环，不连接外部 OpenAI。
 * 注册的工具只有只读的演出查询，测试不会引入抢票、订单修改或提醒写操作。
 */
class AiAssistantServiceTest {

    @Test
    void toolCallLoopInvokesReadOnlyEventQueryAndReturnsAnswer() {
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("getEventDetail")
                .arguments("{\"eventId\":42}")
                .build();
        ChatModel model = new SequenceChatModel(
                ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("演出在上海举行")).build());

        FakeEventService eventService = new FakeEventService(new EventDetailDTO(
                42L, "巡演", "艺人", "上海", "场馆",
                LocalDateTime.parse("2026-08-01T20:00:00"), null, "介绍", 1, 0));
        EventQueryTools eventTools = new EventQueryTools(eventService, new EmptyTicketSkuService());
        ChatAssistant assistant = AiServices.builder(ChatAssistant.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(eventTools)
                .build();

        assertThat(assistant.chat("session-1", "演出在哪里？")).contains("上海");
        assertThat(eventService.requestedEventId).isEqualTo(42L);
    }

    @Test
    void streamReturnsPartialResponsesAndCompletes() throws Exception {
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

    private static final class SequenceChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private int index;

        private SequenceChatModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return responses.get(index++);
        }
    }

    private static final class FakeEventService implements EventService {
        private final EventDetailDTO detail;
        private Long requestedEventId;

        private FakeEventService(EventDetailDTO detail) {
            this.detail = detail;
        }

        @Override public List<EventDTO> listEvents(EventListRequest request) { return List.of(); }
        @Override public EventDetailDTO getEventDetail(Long eventId) {
            requestedEventId = eventId;
            return detail;
        }
        @Override public void invalidateCache(Long eventId) {}
    }

    private static final class EmptyTicketSkuService implements TicketSkuService {
        @Override public List<TicketSkuDTO> listByEvent(Long eventId) { return List.of(); }
        @Override public TicketSkuDTO getSkuDetail(Long skuId) { throw new UnsupportedOperationException(); }
    }
}
