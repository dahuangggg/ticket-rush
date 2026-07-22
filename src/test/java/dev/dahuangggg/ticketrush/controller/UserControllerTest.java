package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.user.UserProfileResponse;
import dev.dahuangggg.ticketrush.entity.User;
import dev.dahuangggg.ticketrush.security.JwtTokenService;
import dev.dahuangggg.ticketrush.service.RefreshTokenStore;
import dev.dahuangggg.ticketrush.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private FakeUserService userService;

    @Test
    void meRequiresJwtToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void meReturnsCurrentUserProfileWhenJwtIsValid() throws Exception {
        User user = User.builder()
                .id(1001L)
                .phone("13800138000")
                .nickName("用户8000")
                .icon("")
                .build();

        String accessToken = jwtTokenService.issueAccessToken(user).accessToken();

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1001))
                .andExpect(jsonPath("$.phone").value("13800138000"))
                .andExpect(jsonPath("$.nickName").value("用户8000"))
                .andExpect(jsonPath("$.icon").value(""));

        assertThat(userService.lastProfileUserId).isEqualTo(1001L);
    }

    @TestConfiguration
    static class UserControllerTestConfig {

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

        private Long lastProfileUserId;

        @Override
        public User findOrCreateByPhone(String phone) {
            return User.builder()
                    .id(1001L)
                    .phone(phone)
                    .nickName("用户8000")
                    .icon("")
                    .build();
        }

        @Override
        public UserProfileResponse getProfile(Long userId) {
            this.lastProfileUserId = userId;
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
