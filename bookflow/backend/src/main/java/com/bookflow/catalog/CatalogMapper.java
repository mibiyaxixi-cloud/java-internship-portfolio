package com.bookflow.catalog;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface CatalogMapper {
    @Select("""
            SELECT id, code, name, category, location, description, status, is_demo AS demo
            FROM device ORDER BY id
            """)
    List<Device> findDevices();

    @Select("""
            SELECT id, code, name, category, location, description, status, is_demo AS demo
            FROM device WHERE id = #{id}
            """)
    Device findDevice(long id);

    // 使用半开区间，避免 DATE(start_at) 让索引失效；参数绑定不会拼接用户输入到 SQL。
    @Select("""
            SELECT s.id, s.device_id, s.start_at, s.end_at, s.status, s.is_demo AS demo,
                   EXISTS(SELECT 1 FROM reservation r WHERE r.active_slot_id = s.id) AS occupied
            FROM equipment_slot s
            WHERE s.device_id = #{deviceId} AND s.start_at >= #{start} AND s.start_at < #{end}
            ORDER BY s.start_at, s.id
            """)
    List<SlotRow> findSlots(@Param("deviceId") long deviceId,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end);

    @Select("""
            SELECT id, code, name, category, location, description, status, is_demo AS demo
            FROM device WHERE id = #{id} FOR UPDATE
            """)
    // 锁查询必须真正到达数据库，不能复用同一 SqlSession 的一级缓存。
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Device lockDevice(long id);

    String SLOT_BY_ID = """
            SELECT s.id, s.device_id, s.start_at, s.end_at, s.status, s.is_demo AS demo,
                   EXISTS(SELECT 1 FROM reservation r WHERE r.active_slot_id = s.id) AS occupied
            FROM equipment_slot s WHERE s.id = #{id}
            """;

    @Select(SLOT_BY_ID)
    SlotRow findSlot(long id);

    @Select(SLOT_BY_ID + " FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    SlotRow lockSlot(long id);
}
