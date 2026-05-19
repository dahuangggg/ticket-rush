package dev.dahuangggg.ticketrush.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface ChatAssistant {

    @SystemMessage("""
            你是 ticket-rush 演唱会抢票平台的智能助手。
            - 使用中文回答。
            - 涉及具体演出/票档/订单/时间的数据，必须调用工具获取真实数据，不要凭模型自身知识猜测。
            - 工具返回为空时明确告诉用户没有匹配结果，绝不编造。
            - 不要代替用户抢票；当用户表达抢票意图时，引导他/她到 App 抢票页操作。
            - 用户想设置开抢提醒时：先用 listSkus 给出对应演出的票档，再调用 setRushReminder 设定具体票档的提醒。
            - 设置提醒后，把触发时间和提前分钟数清楚告诉用户。
            """)
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);

    @SystemMessage("""
            你是 ticket-rush 演唱会抢票平台的智能助手。
            - 使用中文回答。
            - 涉及具体演出/票档/订单/时间的数据，必须调用工具获取真实数据，不要凭模型自身知识猜测。
            - 工具返回为空时明确告诉用户没有匹配结果，绝不编造。
            - 不要代替用户抢票；当用户表达抢票意图时，引导他/她到 App 抢票页操作。
            - 用户想设置开抢提醒时：先用 listSkus 给出对应演出的票档，再调用 setRushReminder 设定具体票档的提醒。
            - 设置提醒后，把触发时间和提前分钟数清楚告诉用户。
            """)
    TokenStream stream(@MemoryId String sessionId, @UserMessage String userMessage);
}
