package com.bookflow.admin;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminMapper {
    @Insert("""
            INSERT INTO device (code, name, category, location, description, status)
            VALUES (#{code}, #{name}, #{category}, #{location}, #{description}, #{status})
            """)
    void insertDevice(DeviceInput input);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Update("""
            UPDATE device SET name = #{input.name}, category = #{input.category}, location = #{input.location},
                              description = #{input.description}, status = #{input.status} WHERE id = #{id}
            """)
    void updateDevice(@Param("id") long id, @Param("input") DeviceInput input);

    @Delete("DELETE FROM device WHERE id = #{id}")
    void deleteDevice(long id);

    @Select("SELECT COUNT(*) FROM waitlist_entry w JOIN equipment_slot s ON s.id=w.slot_id WHERE s.device_id=#{deviceId} AND w.status IN ('WAITING','OFFERED')")
    int countLiveWaitlist(long deviceId);

    @Select("SELECT COUNT(*) FROM equipment_slot WHERE device_id = #{deviceId}")
    int countSlots(long deviceId);

    @Select("""
            SELECT COUNT(*) FROM reservation r JOIN equipment_slot s ON s.id = r.slot_id
            WHERE s.device_id = #{deviceId} AND r.active_slot_id IS NOT NULL AND s.end_at > #{now}
            """)
    int countUnfinishedReservations(@Param("deviceId") long deviceId, @Param("now") LocalDateTime now);

    @Select("SELECT (SELECT COUNT(*) FROM reservation WHERE slot_id = #{slotId}) + (SELECT COUNT(*) FROM waitlist_entry WHERE slot_id = #{slotId})")
    int countHistory(long slotId);

    @Select("""
            SELECT COUNT(*) FROM equipment_slot
            WHERE device_id = #{deviceId} AND id != #{excludedId} AND start_at < #{end} AND end_at > #{start}
            """)
    int countOverlaps(@Param("deviceId") long deviceId, @Param("excludedId") long excludedId,
                      @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Select("""
            SELECT s.id, s.device_id, s.start_at, s.end_at, s.status, s.is_demo AS demo,
                   (EXISTS(SELECT 1 FROM reservation r WHERE r.slot_id = s.id) OR EXISTS(SELECT 1 FROM waitlist_entry w WHERE w.slot_id = s.id)) AS has_history,
                   EXISTS(SELECT 1 FROM reservation r WHERE r.active_slot_id = s.id) AS occupied
            FROM equipment_slot s WHERE s.device_id = #{deviceId} AND s.start_at >= #{start} AND s.start_at < #{end}
            ORDER BY s.start_at, s.id
            """)
    List<AdminSlot> slots(@Param("deviceId") long deviceId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Insert("""
            INSERT INTO equipment_slot (device_id, start_at, end_at, status)
            VALUES (#{deviceId}, #{input.startAt}, #{input.endAt}, #{input.status})
            """)
    void insertSlot(@Param("deviceId") long deviceId, @Param("input") SlotInput input);

    @Update("""
            UPDATE equipment_slot SET start_at = #{input.startAt}, end_at = #{input.endAt}, status = #{input.status}
            WHERE id = #{id}
            """)
    void updateSlot(@Param("id") long id, @Param("input") SlotInput input);

    @Delete("DELETE FROM equipment_slot WHERE id = #{id}")
    void deleteSlot(long id);
}
