package com.bookflow.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("demo")
public class DemoAccountInitializer implements ApplicationRunner {
    private final UserMapper mapper;
    private final PasswordEncoder encoder;
    private final Environment environment;

    public DemoAccountInitializer(UserMapper mapper, PasswordEncoder encoder, Environment environment) {
        this.mapper = mapper;
        this.encoder = encoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 一个事务创建本次缺少的账号；配置缺失时整体回滚，不留下半套账号。
        // 重启不覆盖密码、角色或已有用户名。真实密码不进入日志/迁移文件。
        createIfMissing("student", "演示同学 A", "USER", "DEMO_USER_PASSWORD");
        createIfMissing("student2", "演示同学 B", "USER", "DEMO_SECOND_USER_PASSWORD");
        createIfMissing("admin", "演示管理员", "ADMIN", "DEMO_ADMIN_PASSWORD");
    }

    private void createIfMissing(String username, String displayName, String role, String key) {
        if (mapper.findByUsername(username) != null) return;
        String password = environment.getProperty(key, "");
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 12 || bytes > 72 || password.startsWith("replace_with_")) {
            throw new IllegalStateException("首次创建 demo 账号需配置 " + key + "（12～72 字节）");
        }
        mapper.insertDemo(username, encoder.encode(password), displayName, role);
    }
}
