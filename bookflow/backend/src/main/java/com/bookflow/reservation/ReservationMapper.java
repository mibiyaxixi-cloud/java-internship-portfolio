package com.bookflow.reservation;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ReservationMapper {
    String DETAILS = """
            SELECT r.id, r.user_id, u.username, u.display_name, d.id AS device_id,
                   d.name AS device_name, d.code AS device_code, d.location,
                   r.slot_id, s.start_at, s.end_at, r.status, r.created_at, r.cancelled_at, r.offer_expires_at
            FROM reservation r JOIN app_user u ON u.id = r.user_id
            JOIN equipment_slot s ON s.id = r.slot_id JOIN device d ON d.id = s.device_id
            """;

    @Select(DETAILS + " WHERE r.user_id = #{userId} ORDER BY r.created_at DESC, r.id DESC")
    List<ReservationRow> findMine(long userId);

    @Select(DETAILS + " ORDER BY r.created_at DESC, r.id DESC")
    List<ReservationRow> findAll();

    @Select(DETAILS + " WHERE r.id = #{id}")
    ReservationRow findDetail(long id);

    @Select("SELECT id, user_id, slot_id, status FROM reservation WHERE id = #{id}")
    ReservationRecord findRecord(long id);

    @Select("SELECT id, user_id, slot_id, status FROM reservation WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReservationRecord lockRecord(long id);

    @Insert("INSERT INTO reservation (user_id, slot_id) VALUES (#{userId}, #{slotId})")
    void insert(@Param("userId") long userId, @Param("slotId") long slotId);

    @Select("SELECT id, user_id, slot_id, status FROM reservation WHERE active_slot_id = #{slotId}")
    ReservationRecord active(long slotId);

    @Insert("INSERT INTO reservation(user_id,slot_id,status,offer_expires_at) VALUES(#{userId},#{slotId},'HELD',#{expires})")
    void hold(@Param("userId") long userId, @Param("slotId") long slotId, @Param("expires") LocalDateTime expires);

    @Update("UPDATE reservation SET status='CONFIRMED' WHERE id=#{id} AND status='HELD' AND offer_expires_at>#{now}")
    int confirmHold(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE reservation SET status='EXPIRED' WHERE id=#{id} AND status='HELD'")
    int expireHold(long id);

    // 连接范围内的值，只允许在 insert 所在事务/连接中读取。
    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE reservation SET status = 'CANCELLED', cancelled_at = #{now}
            WHERE id = #{id} AND user_id = #{userId} AND status IN ('CONFIRMED','HELD')
            """)
    int cancel(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);
}
