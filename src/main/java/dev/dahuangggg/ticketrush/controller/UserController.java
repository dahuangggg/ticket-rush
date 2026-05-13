package dev.dahuangggg.ticketrush.controller;

import dev.dahuangggg.ticketrush.dto.user.UserProfileResponse;
import dev.dahuangggg.ticketrush.security.UserContext;
import dev.dahuangggg.ticketrush.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查询当前登录用户资料。
     *
     * 这个接口不从请求参数里拿 userId，而是从 UserContext 里取。
     * UserContext 的值由 JWT 拦截器解析 Authorization 请求头得到，
     * 这样可以避免前端伪造 userId 查询别人的资料。
     */
    @GetMapping("/me")
    public UserProfileResponse me() {
        return userService.getProfile(UserContext.getUserId());
    }
}
