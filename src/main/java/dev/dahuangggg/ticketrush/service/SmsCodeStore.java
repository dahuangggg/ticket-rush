package dev.dahuangggg.ticketrush.service;

/**
 * 短信验证码存储接口。
 *
 * 这里抽象出接口，是为了让业务层只关心“保存、匹配、删除验证码”这三个动作。
 * 当前实现使用 Redis；测试环境可以替换成内存 Fake；以后也可以换成其他缓存组件。
 */
public interface SmsCodeStore {

    /**
     * 保存验证码。
     *
     * 实现类需要自己负责过期时间，避免验证码永久有效。
     */
    void save(String phone, String code);

    /**
     * 判断用户提交的验证码是否和存储中的验证码一致。
     */
    boolean matches(String phone, String code);

    /**
     * 删除验证码。
     *
     * 登录成功后必须删除验证码，保证验证码是一次性的。
     */
    void delete(String phone);
}
