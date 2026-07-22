package dev.dahuangggg.ticketrush.infrastructure.cache;

/**
 * Caffeine 详情缓存同时承载普通与热点活动，必须保留来源类型。
 * 否则普通活动的本地命中会被热点读取路径误判，动态热点检测将停止计数。
 */
public record EventDetailLocalValue(boolean hot, String json) {

    public static EventDetailLocalValue normal(String json) {
        return new EventDetailLocalValue(false, json);
    }

    public static EventDetailLocalValue hot(String json) {
        return new EventDetailLocalValue(true, json);
    }
}
