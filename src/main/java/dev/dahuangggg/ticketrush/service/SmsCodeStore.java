package dev.dahuangggg.ticketrush.service;

/**
 * 短信验证码存储接口。
 *
 * 这个接口刻意提供“验证并消费”这一项完整业务能力，而不是暴露 get/delete 等
 * Redis 风格的低层操作。调用方因此不可能误写出“先查询、后删除”的竞态窗口；
 * Redis 实现可以用 Lua 保证校验、失败计数和一次性消费在同一个原子操作里完成。
 */
public interface SmsCodeStore {

    /**
     * 保存验证码。
     *
     * 实现类需要自己负责过期时间，避免验证码永久有效。
     */
    void save(String phone, String code);

    /**
     * 原子校验并消费验证码。
     *
     * <p>验证码正确时必须在返回 {@link VerificationResult#VERIFIED} 前删除；输入错误时
     * 必须增加失败次数；达到上限后验证码立即失效。这样即使两个登录请求并发到达，
     * 也最多只有一个请求能够消费成功。</p>
     */
    VerificationResult verifyAndConsume(String phone, String submittedCode);

    enum VerificationResult {
        VERIFIED,
        INVALID,
        TOO_MANY_ATTEMPTS
    }
}
