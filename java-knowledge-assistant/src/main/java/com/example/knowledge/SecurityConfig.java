package com.example.knowledge;

import org.springframework.context.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService userDetailsService(KnowledgeMapper db) {
        return username -> {
            var a = db.account(username);
            if (a == null) throw new UsernameNotFoundException("账号或密码错误");
            return User.withUsername(a.username()).password(a.passwordHash()).roles(a.role()).build();
        };
    }
    @Bean SecurityFilterChain security(HttpSecurity http,RateLimits limits,GuardProperties guard,com.fasterxml.jackson.databind.ObjectMapper json) throws Exception {
        // Keep the default session CSRF repository; the SPA obtains a masked token via /api/csrf.
        return http.addFilterBefore(new RateLimitFilter(limits,guard,json),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class).authorizeHttpRequests(a -> a
                .requestMatchers("/api/csrf", "/api/login", "/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req,res,auth) -> { res.setContentType("application/json"); res.getWriter().write("{\"ok\":true}"); })
                .failureHandler((req,res,ex) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"code\":\"LOGIN_FAILED\",\"message\":\"账号或密码错误\"}"); }))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,ex) -> { res.setStatus(401); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录\"}"); })
                .accessDeniedHandler((req,res,ex) -> { res.setStatus(403); res.setContentType("application/json;charset=UTF-8"); res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"权限不足或 CSRF 已失效，请重新登录\"}"); }))
            .build();
    }
    @Bean ApplicationRunner backfillIndexMetadata(KnowledgeMapper db,AppProperties props,DocumentService documents) {
        return args -> db.backfillProfile(props.profile(),props.collection(),documents.indexConfig());
    }
    @Bean ApplicationRunner seedAccounts(KnowledgeMapper db, PasswordEncoder encoder, AppProperties props) {
        return args -> {
            seed(db,encoder,"admin","ADMIN",props.adminPassword());
            seed(db,encoder,"reader","READER",props.readerPassword());
            seed(db,encoder,"reader2","READER",props.reader2Password());
        };
    }
    private void seed(KnowledgeMapper db, PasswordEncoder encoder, String name, String role, String password) {
        if (db.account(name) != null) return;
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException(name + " 首次启动需环境变量密码，至少 12 字符、最多 72 个 UTF-8 字节");
        db.addAccount(name,encoder.encode(password),role);
    }
}
