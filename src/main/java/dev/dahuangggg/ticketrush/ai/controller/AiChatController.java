package dev.dahuangggg.ticketrush.ai.controller;

import dev.dahuangggg.ticketrush.ai.dto.ChatRequest;
import dev.dahuangggg.ticketrush.ai.service.AiAssistantService;
import dev.langchain4j.data.message.ChatMessage;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

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
        String answer;
        try {
            answer = service.chat(req.getSessionId(), req.getMessage());
        } catch (Exception e) {
            log.warn("AI chat failed sessionId={}", req.getSessionId(), e);
            answer = "抱歉，AI 服务暂时不可用：" + e.getClass().getSimpleName();
        }
        return Map.of("sessionId", req.getSessionId(), "answer", answer);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessage> history(@PathVariable String sessionId) {
        return service.history(sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void clear(@PathVariable String sessionId) {
        service.clear(sessionId);
    }
}
