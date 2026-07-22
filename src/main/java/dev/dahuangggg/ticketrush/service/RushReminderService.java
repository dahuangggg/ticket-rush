package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.reminder.ReminderDTO;
import dev.dahuangggg.ticketrush.dto.reminder.ReminderCreationResponse;

import java.util.List;

public interface RushReminderService {

    /** 用户显式设置或更新开抢提醒（按 SKU）；该能力不注册为 AI Tool。 */
    ReminderCreationResponse setReminder(Long userId, Long skuId, Integer leadMinutes);

    /** 列表查询：filterStatus 为 null 不过滤；unreadOnly=true 只返回 read_flag=0。 */
    List<ReminderDTO> list(Long userId, String filterStatus, boolean unreadOnly);

    /** 标记已读。归属校验失败抛 ReminderNotFoundException（404）。 */
    void markRead(Long userId, Long reminderId);

    /** 用户取消提醒：status=CANCELLED + ZREM。归属校验失败抛 ReminderNotFoundException。 */
    void cancel(Long userId, Long reminderId);

    /** 调度器调用：把单条 PENDING 标为 FIRED（CAS）。返回是否实际更新。 */
    boolean fire(Long reminderId);
}
