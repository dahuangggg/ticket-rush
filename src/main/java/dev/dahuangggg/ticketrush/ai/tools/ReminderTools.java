package dev.dahuangggg.ticketrush.ai.tools;

import dev.dahuangggg.ticketrush.dto.reminder.ReminderToolResult;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.RushReminderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ReminderTools {

    private final RushReminderService reminderService;

    public ReminderTools(RushReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Tool("为指定票档(SKU)设置开抢提醒。开抢前 leadMinutes 分钟通过站内消息通知用户。使用前应先调用 listSkus 确认 skuId。")
    public ReminderToolResult setRushReminder(
            @P("票档 SKU ID") Long skuId,
            @P("提前多少分钟提醒，默认5，范围1-1440") Integer leadMinutes) {
        Long userId = UserContext.getUserId();
        Objects.requireNonNull(userId, "missing user context");
        return reminderService.setReminder(userId, skuId, leadMinutes);
    }
}
