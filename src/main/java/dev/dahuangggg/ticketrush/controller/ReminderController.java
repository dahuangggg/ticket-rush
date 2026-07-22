package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.reminder.ReminderDTO;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderCreationRequest;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderCreationResponse;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.RushReminderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final RushReminderService service;

    public ReminderController(RushReminderService service) {
        this.service = service;
    }

    /** 查询当前用户的提醒。 */
    @GetMapping
    public List<ReminderDTO> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false, defaultValue = "false") boolean unread) {
        return service.list(UserContext.getUserId(), status, unread);
    }

    /** 写操作只能由已认证用户通过普通业务 API 显式触发，AI 注册表不可达。 */
    @PostMapping
    public ReminderCreationResponse create(@Valid @RequestBody ReminderCreationRequest request) {
        return service.setReminder(
                UserContext.getUserId(), request.skuId(), request.leadMinutes());
    }

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        service.markRead(UserContext.getUserId(), id);
    }

    @DeleteMapping("/{id}")
    public void cancel(@PathVariable Long id) {
        service.cancel(UserContext.getUserId(), id);
    }
}
