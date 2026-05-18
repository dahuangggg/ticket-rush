package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.reminder.ReminderDTO;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.RushReminderService;
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

    @PostMapping("/{id}/read")
    public void markRead(@PathVariable Long id) {
        service.markRead(UserContext.getUserId(), id);
    }

    @DeleteMapping("/{id}")
    public void cancel(@PathVariable Long id) {
        service.cancel(UserContext.getUserId(), id);
    }
}
