package com.bookflow.auth;

import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {
    @Select("""
            SELECT id, username, password_hash, display_name, role, enabled, is_demo AS demo
            FROM app_user WHERE username = #{username}
            """)
    UserAccount findByUsername(String username);

    @Insert("""
            INSERT INTO app_user (username, password_hash, display_name, role, enabled, is_demo)
            VALUES (#{username}, #{passwordHash}, #{displayName}, #{role}, TRUE, TRUE)
            """)
    void insertDemo(@Param("username") String username, @Param("passwordHash") String passwordHash,
                    @Param("displayName") String displayName, @Param("role") String role);
}
