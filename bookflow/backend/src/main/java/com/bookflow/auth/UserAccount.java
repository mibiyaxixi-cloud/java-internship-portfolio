package com.bookflow.auth;

public record UserAccount(long id, String username, String passwordHash, String displayName,
                          String role, boolean enabled, boolean demo) {}
