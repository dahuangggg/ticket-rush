package dev.dahuangggg.ticketrush.service;

import dev.dahuangggg.ticketrush.dto.event.EventDetailDTO;

/**
 * 动态热点检测：聚合活动访问量，超过阈值时自动触发缓存预热。
 * 仅作为运营漏标后的补救手段，不是缓存策略的第一道防线。
 */
public interface HotSpotDetector {

    /**
     * 记录一次活动访问。Implementation 必须保证调用路径仅执行有界的内存操作，
     * 外部 Redis I/O 由聚合刷新任务完成。
     *
     * @param eventId 活动 ID
     * @param isHot   活动当前是否为热点（is_hot=1），用于判断是否需要升级
     * @param detail  活动详情 DTO，用于触发预热时写入缓存（可为 null，表示无需预热）
     */
    void trackAccess(Long eventId, boolean isHot, EventDetailDTO detail);
}
