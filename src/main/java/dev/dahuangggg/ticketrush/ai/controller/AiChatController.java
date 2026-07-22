package dev.dahuangggg.ticketrush.ai.controller;

import dev.dahuangggg.ticketrush.ai.dto.AiChatResponse;
import dev.dahuangggg.ticketrush.ai.dto.ChatRequest;
import dev.dahuangggg.ticketrush.ai.service.AiAssistantService;
import dev.dahuangggg.ticketrush.exception.AiServiceUnavailableException;
import dev.dahuangggg.ticketrush.security.LoginUser;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.service.TokenStream;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/ai")
@Validated
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);
    private static final long STREAM_TIMEOUT_MILLIS = 60_000L;
    private final AiAssistantService service;

    public AiChatController(AiAssistantService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public AiChatResponse chat(@Valid @RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        try {
            String answer = service.chat(userId, req.sessionId(), req.message());
            return new AiChatResponse(req.sessionId(), answer);
        } catch (Exception e) {
            log.warn("AI chat failed userId={} sessionId={}", userId, req.sessionId(), e);
            throw new AiServiceUnavailableException(e);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        LoginUser loginUser = UserContext.get();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean terminal = new AtomicBoolean();

        emitter.onTimeout(() -> {
            terminal.set(true);
            UserContext.clear();
            emitter.complete();
        });
        emitter.onCompletion(UserContext::clear);
        emitter.onError(ignored -> UserContext.clear());

        try {
            TokenStream stream = service.stream(userId, req.sessionId(), req.message());
            stream.beforeToolExecution(ignored -> {
                        // 模型回调在线程池执行，ThreadLocal 不会自动从 MVC 请求线程传播。
                        // 先清理再设置，避免复用线程携带之前任务留下的身份。
                        UserContext.clear();
                        UserContext.set(loginUser);
                    })
                    .onToolExecuted(ignored -> UserContext.clear())
                    .onPartialResponse(token -> {
                        UserContext.clear();
                        if (!terminal.get()) send(emitter, "delta", token);
                    })
                    .onCompleteResponse(response -> {
                        UserContext.clear();
                        if (terminal.compareAndSet(false, true)) {
                            send(emitter, "done", "");
                            emitter.complete();
                        }
                    })
                    .onError(error -> {
                        UserContext.clear();
                        log.warn("AI stream failed userId={} sessionId={}", userId, req.sessionId(), error);
                        if (terminal.compareAndSet(false, true)) {
                            send(emitter, "error", "抱歉，AI 服务暂时不可用，请稍后再试。");
                            emitter.complete();
                        }
                    })
                    .start();
        } catch (Exception e) {
            log.warn("AI stream start failed userId={} sessionId={}", userId, req.sessionId(), e);
            if (terminal.compareAndSet(false, true)) {
                send(emitter, "error", "抱歉，AI 服务暂时不可用，请稍后再试。");
                emitter.complete();
            }
        } finally {
            // Spring MVC 异步响应结束前可能会归还当前容器线程；不能等 SSE 完成后
            // 才由 interceptor.afterCompletion 清理 ThreadLocal。
            UserContext.clear();
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
    public List<ChatMessage> history(
            @PathVariable
            @Size(max = 64, message = "会话 ID 最长为 64 个字符")
            @Pattern(regexp = "[A-Za-z0-9._-]+", message = "会话 ID 格式不正确")
            String sessionId) {
        return service.history(UserContext.getUserId(), sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public void clear(
            @PathVariable
            @Size(max = 64, message = "会话 ID 最长为 64 个字符")
            @Pattern(regexp = "[A-Za-z0-9._-]+", message = "会话 ID 格式不正确")
            String sessionId) {
        service.clear(UserContext.getUserId(), sessionId);
    }
}
