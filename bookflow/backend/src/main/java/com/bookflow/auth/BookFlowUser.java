package com.bookflow.auth;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

// 认证后的身份由数据库生成；任何预约请求都不能自行传 userId 来冒充别人。
public class BookFlowUser extends User {
    private final long id;
    private final String displayName;
    private final String role;
    private final boolean demo;

    public BookFlowUser(UserAccount account) {
        super(account.username(), account.passwordHash(), account.enabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        this.id = account.id();
        this.displayName = account.displayName();
        this.role = account.role();
        this.demo = account.demo();
    }

    public long id() { return id; }
    public CurrentUser view() { return new CurrentUser(id, getUsername(), displayName, role, demo); }
}
