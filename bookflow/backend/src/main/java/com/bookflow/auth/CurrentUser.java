package com.bookflow.auth;

// 响应只包含必要资料，绝不序列化 UserDetails/passwordHash。
public record CurrentUser(long id, String username, String displayName, String role, boolean demo) {}
