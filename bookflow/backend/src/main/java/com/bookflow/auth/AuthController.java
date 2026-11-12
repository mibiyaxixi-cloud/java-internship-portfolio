package com.bookflow.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        // 触发延迟 token 的生成；浏览器从 JSON 读取并在写请求头中提交。
        // 登录/退出会轮换 token，因此客户端每次写入前获取当前会话的 token。
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public CurrentUser me(@AuthenticationPrincipal BookFlowUser user) { return user.view(); }

    public record CsrfView(String headerName, String token) {}
}
