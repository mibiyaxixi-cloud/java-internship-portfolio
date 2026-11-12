package com.bookflow.config;

import com.bookflow.auth.BookFlowUser;
import com.bookflow.auth.DatabaseUserDetailsService;
import com.bookflow.common.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    DaoAuthenticationProvider authenticationProvider(DatabaseUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        // 使用框架认证过滤器持久化 SecurityContext、变更会话 ID；不自行拼装登录态。
        // CSRF 保持默认 HttpSession 存储及 XOR 处理，包含登录和退出接口。
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/devices", "/api/devices/*/slots").permitAll()
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/reservations/**", "/api/waitlist/**", "/api/auth/me").authenticated()
                .anyRequest().denyAll())
            .requestCache(cache -> cache.disable())
            .formLogin(login -> login.loginPage("/")
                .loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) ->
                    write(response, mapper, 200, ((BookFlowUser) authentication.getPrincipal()).view()))
                .failureHandler((request, response, exception) -> {
                    if (exception instanceof AuthenticationServiceException) {
                        write(response, mapper, 503, new ApiError("AUTH_UNAVAILABLE", "登录服务暂时不可用，请稍后重试"));
                    } else {
                        write(response, mapper, 401, new ApiError("INVALID_CREDENTIALS", "账号或密码错误，或账号已停用"));
                    }
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> {
                    response.setStatus(204);
                }))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    write(response, mapper, 401, new ApiError("UNAUTHENTICATED", "请先登录")))
                .accessDeniedHandler((request, response, exception) ->
                    write(response, mapper, 403, exception instanceof CsrfException
                        ? new ApiError("CSRF_INVALID", "安全凭证已失效，请刷新页面后重新操作")
                        : new ApiError("FORBIDDEN", "没有权限执行此操作"))));
        return http.build();
    }

    private void write(HttpServletResponse response, ObjectMapper mapper, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }
}
