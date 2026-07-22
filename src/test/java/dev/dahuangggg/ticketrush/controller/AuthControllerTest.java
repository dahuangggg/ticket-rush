package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.user.UserProfileResponse;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.service.RefreshTokenStore;
import dev.dahuangggg.ticketrush.service.SmsCodeStore;
import dev.dahuangggg.ticketrush.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeSmsCodeStore smsCodeStore;

    @Autowired
    private FakeUserService userService;

    @Autowired
    private FakeRefreshTokenStore refreshTokenStore;

    @BeforeEach
    void resetFakes() {
        smsCodeStore.reset();
        userService.lastLoginPhone = null;
    }

    @Test
    void sendSmsCodeStoresCodeForPhone() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "13800138000"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("验证码已发送"));

        assertThat(smsCodeStore.savedPhone).isEqualTo("13800138000");
        assertThat(smsCodeStore.savedCode).matches("\\d{6}");
    }

    @Test
    void loginWithValidSmsCodeReturnsJwt() throws Exception {
        smsCodeStore.savedPhone = "13800138000";
        smsCodeStore.savedCode = "123456";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "13800138000",
                                  "code": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.expiresIn").value(7200))
                .andExpect(jsonPath("$.refreshToken", not(blankOrNullString())));

        assertThat(smsCodeStore.consumedPhone).isEqualTo("13800138000");
        assertThat(smsCodeStore.savedCode).isNull();
        assertThat(userService.lastLoginPhone).isEqualTo("13800138000");
    }

    @Test
    void refreshWithValidTokenReturnsNewAccessToken() throws Exception {
        String token = refreshTokenStore.issue(1001L);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "%s"
                                }
                                """.formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", not(blankOrNullString())))
                .andExpect(jsonPath("$.refreshToken").value(token));
    }

    @Test
    void refreshWithInvalidTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "invalid-token"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutInvalidatesRefreshToken() throws Exception {
        String token = refreshTokenStore.issue(1001L);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "%s"
                                }
                                """.formatted(token)))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenStore.getUserId(token)).isNull();
    }

    @Test
    void loginWithInvalidSmsCodeReturnsBadRequest() throws Exception {
        smsCodeStore.savedPhone = "13800138000";
        smsCodeStore.savedCode = "123456";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "13800138000",
                                  "code": "000000"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SMS_CODE"))
                .andExpect(jsonPath("$.message").value("验证码错误或已过期"));
    }

    @Test
    void loginAfterTooManySmsFailuresReturnsTooManyRequests() throws Exception {
        smsCodeStore.forcedResult = SmsCodeStore.VerificationResult.TOO_MANY_ATTEMPTS;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "13800138000",
                                  "code": "000000"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SMS_ATTEMPTS_EXCEEDED"));

        assertThat(userService.lastLoginPhone).isNull();
    }

    @TestConfiguration
    static class AuthControllerTestConfig {

        @Bean
        @Primary
        FakeSmsCodeStore fakeSmsCodeStore() {
            return new FakeSmsCodeStore();
        }

        @Bean
        @Primary
        FakeRefreshTokenStore fakeRefreshTokenStore() {
            return new FakeRefreshTokenStore();
        }

        @Bean
        @Primary
        FakeUserService fakeUserService() {
            return new FakeUserService();
        }
    }

    static class FakeSmsCodeStore implements SmsCodeStore {

        private String savedPhone;
        private String savedCode;
        private String consumedPhone;
        private VerificationResult forcedResult;

        void reset() {
            savedPhone = null;
            savedCode = null;
            consumedPhone = null;
            forcedResult = null;
        }

        @Override
        public void save(String phone, String code) {
            this.savedPhone = phone;
            this.savedCode = code;
        }

        @Override
        public VerificationResult verifyAndConsume(String phone, String code) {
            this.consumedPhone = phone;
            if (forcedResult != null) return forcedResult;
            if (phone.equals(savedPhone) && code.equals(savedCode)) {
                savedCode = null;
                return VerificationResult.VERIFIED;
            }
            return VerificationResult.INVALID;
        }
    }

    static class FakeRefreshTokenStore implements RefreshTokenStore {

        private final Map<String, Long> store = new HashMap<>();

        @Override
        public String issue(Long userId) {
            String token = UUID.randomUUID().toString();
            store.put(token, userId);
            return token;
        }

        @Override
        public Long getUserId(String token) {
            return store.get(token);
        }

        @Override
        public void delete(String token) {
            store.remove(token);
        }
    }

    static class FakeUserService implements UserService {

        private String lastLoginPhone;

        @Override
        public User findOrCreateByPhone(String phone) {
            this.lastLoginPhone = phone;
            return User.builder()
                    .id(1001L)
                    .phone(phone)
                    .nickName("用户8000")
                    .icon("")
                    .build();
        }

        @Override
        public UserProfileResponse getProfile(Long userId) {
            return new UserProfileResponse(userId, "13800138000", "用户8000", "");
        }

        @Override
        public User findById(Long userId) {
            return User.builder()
                    .id(userId)
                    .phone("13800138000")
                    .nickName("用户8000")
                    .icon("")
                    .build();
        }
    }
}
