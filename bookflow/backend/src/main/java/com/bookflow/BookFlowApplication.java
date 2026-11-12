package com.bookflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 登录使用数据库 UserDetailsService，不启用 Spring Boot 自动生成的默认用户。
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class BookFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookFlowApplication.class, args);
    }
}
