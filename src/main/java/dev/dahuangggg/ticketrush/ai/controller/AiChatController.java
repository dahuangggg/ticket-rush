package dev.dahuangggg.ticketrush.ai.controller;

import dev.dahuangggg.ticketrush.ai.dto.ChatRequest;
import dev.dahuangggg.ticketrush.ai.service.AiAssistantService;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private final AiAssistantService service;

    public AiChatController(AiAssistantService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@Valid @RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        String answer;
        try {
            answer = service.chat(userId, req.getSessionId(), req.getMessage());
        } catch (Exception e) {
            log.warn("AI chat failed userId={} sessionId={}", userId, req.getSessionId(), e);
            answer = "抱歉，AI 服务暂时不可用：" + e.getClass().getSimpleName();
        }
        return Map.of("sessionId", req.getSessionId(), "answer", answer);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        LoginUser loginUser = UserContext.get();
        SseEmitter emitter = new SseEmitter(0L);

        try {
            TokenStream stream = service.stream(userId, req.getSessionId(), req.getMessage());
            stream.beforeToolExecution(ignored -> UserContext.set(loginUser))
                    .onToolExecuted(ignored -> UserContext.clear())
                    .onPartialResponse(token -> send(emitter, "delta", token))
                    .onCompleteResponse(response -> {
                        UserContext.clear();
                        send(emitter, "done", "");
                        emitter.complete();
                    })
                    .onError(error -> {
                        UserContext.clear();
                        log.warn("AI stream failed userId={} sessionId={}", userId, req.getSessionId(), error);
                        send(emitter, "error", "抱歉，AI 服务暂时不可用，请稍后再试。");
                        emitter.complete();
                    })
                    .start();
        } catch (Exception e) {
            log.warn("AI stream start failed userId={} sessionId={}", userId, req.getSessionId(), e);
            send(emitter, "error", "抱歉，AI 服务暂时不可用，请稍后再试。");
            emitter.complete();
        }

        return emitter;
    }

    private static void send(SseEmitter emitter, String eventName, String data) {
        try {
            String encoded = Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
            emitter.send(SseEmitter.event().name(eventName).data(encoded));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> history(@PathVariable String sessionId) {
        return service.history(UserContext.getUserId(), sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        service.clear(UserContext.getUserId(), sessionId);
    }
}
