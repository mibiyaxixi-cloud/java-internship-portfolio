package com.bookflow.idempotency;
import org.apache.ibatis.annotations.*;
@Mapper
public interface IdempotencyMapper {
    // 唯一键冲突时等待原事务结束；同一用户的同一 key 在数据库中串行化。
    @Insert("""
        INSERT INTO idempotent_request (user_id, request_key, operation, resource_id)
        VALUES (#{userId}, #{key}, #{operation}, #{resourceId})
        ON DUPLICATE KEY UPDATE user_id = idempotent_request.user_id
        """)
    void claim(@Param("userId") long userId, @Param("key") String key,
               @Param("operation") String operation, @Param("resourceId") long resourceId);
    @Select("""
        SELECT operation, resource_id, response_status, response_json FROM idempotent_request
        WHERE user_id = #{userId} AND request_key = #{key} FOR UPDATE
        """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    StoredRequest lock(@Param("userId") long userId, @Param("key") String key);
    @Update("""
        UPDATE idempotent_request SET response_status = #{status}, response_json = #{json}
        WHERE user_id = #{userId} AND request_key = #{key}
        """)
    void complete(@Param("userId") long userId, @Param("key") String key,
                  @Param("status") int status, @Param("json") String json);
}
