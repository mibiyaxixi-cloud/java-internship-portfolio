package com.bookflow.waitlist;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
@Mapper
public interface WaitlistMapper {
    String ENTRY = "SELECT id, user_id, slot_id, status, queued_at, offered_at, expires_at, completed_at, reservation_id, reason FROM waitlist_entry";
    String VIEW = """
        SELECT w.id, w.user_id, u.username, u.display_name, w.slot_id, d.name AS device_name,
        d.code AS device_code, s.start_at, s.end_at, w.status, w.queued_at, w.expires_at,
        w.reservation_id, w.reason,
        CASE WHEN w.status = 'WAITING' THEN
          (SELECT COUNT(*) + 1 FROM waitlist_entry q WHERE q.slot_id = w.slot_id AND q.id < w.id AND q.status IN ('WAITING','OFFERED'))
        ELSE 0 END AS position
        FROM waitlist_entry w JOIN app_user u ON u.id = w.user_id
        JOIN equipment_slot s ON s.id = w.slot_id JOIN device d ON d.id = s.device_id
        """;
    @Select(ENTRY + " WHERE id = #{id}") WaitlistEntry find(long id);
    @Select(ENTRY + " WHERE slot_id = #{slotId} AND status = 'OFFERED' LIMIT 1") WaitlistEntry offered(long slotId);
    @Select(ENTRY + " WHERE slot_id = #{slotId} AND status = 'WAITING' ORDER BY id LIMIT 1") WaitlistEntry next(long slotId);
    @Select(VIEW + " WHERE w.user_id = #{userId} ORDER BY w.id DESC") List<WaitlistView> mine(long userId);
    @Select(VIEW + " ORDER BY w.id DESC") List<WaitlistView> all();
    @Select(VIEW + " WHERE w.id = #{id}") WaitlistView detail(long id);
    @Select("SELECT COUNT(*) FROM waitlist_entry WHERE slot_id = #{slotId} AND status IN ('WAITING','OFFERED')") int liveCount(long slotId);
    @Select("SELECT COUNT(*) FROM waitlist_entry WHERE slot_id = #{slotId} AND live_user_id = #{userId}")
    int hasLive(@Param("slotId") long slotId, @Param("userId") long userId);
    @Select("SELECT enabled FROM app_user WHERE id = #{id}") boolean enabled(long id);
    @Insert("INSERT INTO waitlist_entry(user_id,slot_id,queued_at) VALUES(#{userId},#{slotId},#{now})")
    void insert(@Param("userId") long userId, @Param("slotId") long slotId, @Param("now") LocalDateTime now);
    @Select("SELECT LAST_INSERT_ID()") long lastInsertId();
    @Update("""
        UPDATE waitlist_entry SET status = 'OFFERED', offered_at = #{now}, expires_at = #{expires}, reservation_id = #{reservationId}
        WHERE id = #{id} AND status = 'WAITING'
        """)
    int offer(@Param("id") long id, @Param("reservationId") long reservationId,
              @Param("now") LocalDateTime now, @Param("expires") LocalDateTime expires);
    @Update("""
        UPDATE waitlist_entry SET status = #{status}, completed_at = #{now}, reason = #{reason}
        WHERE id = #{id} AND status IN ('WAITING','OFFERED')
        """)
    int finish(@Param("id") long id, @Param("status") String status, @Param("now") LocalDateTime now, @Param("reason") String reason);
    @Update("""
        UPDATE waitlist_entry SET status='EXPIRED', completed_at=#{now}, reason=#{reason}
        WHERE slot_id=#{slotId} AND status='WAITING'
        """)
    void expireWaiting(@Param("slotId") long slotId, @Param("now") LocalDateTime now, @Param("reason") String reason);
    @Insert("""
        INSERT INTO waitlist_task(slot_id,status,next_run_at,updated_at) VALUES(#{slotId},#{status},#{next},#{now})
        ON DUPLICATE KEY UPDATE status=#{status}, next_run_at=#{next}, updated_at=#{now}, failure_count=0, last_error=NULL
        """)
    void schedule(@Param("slotId") long slotId, @Param("status") String status,
                  @Param("next") LocalDateTime next, @Param("now") LocalDateTime now);
    @Select("SELECT slot_id FROM waitlist_task WHERE status='PENDING' AND next_run_at<=#{now} ORDER BY next_run_at,slot_id LIMIT 100")
    List<Long> due(LocalDateTime now);
    @Select("SELECT slot_id,status,next_run_at,failure_count,last_error FROM waitlist_task WHERE slot_id=#{slotId}")
    WaitlistTask task(long slotId);
    @Select("SELECT slot_id,status,next_run_at,failure_count,last_error FROM waitlist_task ORDER BY status DESC,next_run_at,slot_id")
    List<WaitlistTask> tasks();
    // 业务事务失败后保留持久化任务并稍后重试；不覆盖另一工作进程已更新的未来期限。
    @Update("""
        UPDATE waitlist_task SET next_run_at=#{retryAt}, failure_count=failure_count+1, last_error=#{error}, updated_at=#{now}
        WHERE slot_id=#{slotId} AND status='PENDING' AND next_run_at<=#{now}
        """)
    void failed(@Param("slotId") long slotId, @Param("now") LocalDateTime now,
                @Param("retryAt") LocalDateTime retryAt, @Param("error") String error);
}
