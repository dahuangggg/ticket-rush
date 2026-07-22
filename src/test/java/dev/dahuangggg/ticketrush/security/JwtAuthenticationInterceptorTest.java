package dev.dahuangggg.ticketrush.security;

import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class JwtAuthenticationInterceptorTest {

    private final JwtAuthenticationInterceptor interceptor =
            new JwtAuthenticationInterceptor(mock(JwtTokenService.class));

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void failedAuthenticationCannotReuseStaleThreadContext() {
        UserContext.set(new LoginUser(99L, "13900000000", "admin"));
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThatThrownBy(() -> interceptor.preHandle(request, null, new Object()))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(UserContext.get()).isNull();
    }

    @Test
    void asyncHandoffClearsContainerThreadContext() {
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        interceptor.afterConcurrentHandlingStarted(null, null, new Object());

        assertThat(UserContext.get()).isNull();
    }
}
