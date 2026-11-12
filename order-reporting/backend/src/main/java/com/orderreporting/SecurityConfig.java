package com.orderreporting;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(CatalogMapper mapper) {
        return username -> {
            var a=mapper.account(username);
            if(a==null) throw new UsernameNotFoundException("Unknown account");
            return User.withUsername(a.username()).password(a.passwordHash()).roles(a.role()).build();
        };
    }
    static void error(HttpServletResponse r,int status,String code,String message) throws IOException {
        r.setStatus(status);r.setContentType("application/json;charset=UTF-8");
        r.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a->a.requestMatchers("/api/auth/csrf","/api/auth/login").permitAll().anyRequest().authenticated())
            .formLogin(f->f.loginProcessingUrl("/api/auth/login")
                .successHandler((q,r,a)->{r.setStatus(204);})
                .failureHandler((q,r,e)->error(r,401,"LOGIN_FAILED","用户名或密码错误")))
            .logout(l->l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,r,a)->r.setStatus(204)))
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->error(r,401,"UNAUTHENTICATED","请先登录"))
                .accessDeniedHandler((q,r,x)->error(r,403,"FORBIDDEN","权限不足或 CSRF 凭证无效")))
            .requestCache(c->c.disable()).build();
    }
}
