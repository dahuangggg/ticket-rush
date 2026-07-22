package dev.dahuangggg.ticketrush.ai.dto;

import dev.dahuangggg.ticketrush.dto.event.EventDTO;

import java.util.List;

/**
 * 演出搜索工具的稳定结果。
 *
 * <p>参数错误也是工具可以解释的业务结果，不让模型把 Java 异常或空列表误解成
 * “没有演出”。</p>
 */
public record EventSearchResult(boolean ok, String error, List<EventDTO> events) {

    public static EventSearchResult success(List<EventDTO> events) {
        return new EventSearchResult(true, null, List.copyOf(events));
    }

    public static EventSearchResult invalid(String error) {
        return new EventSearchResult(false, error, List.of());
    }
}
