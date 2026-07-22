package dev.dahuangggg.ticketrush.security;

import dev.dahuangggg.ticketrush.exception.ForbiddenException;
import dev.dahuangggg.ticketrush.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthInterceptorTest {

    private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor();

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void missingAuthenticationIsUnauthorized() {
        assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void authenticatedNonAdminIsForbidden() {
        UserContext.set(new LoginUser(7L, "13800138000", "user"));

        assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void adminIsAllowed() {
        UserContext.set(new LoginUser(7L, "13800138000", "admin"));

        org.assertj.core.api.Assertions.assertThat(
                interceptor.preHandle(null, null, new Object())).isTrue();
    }
}
