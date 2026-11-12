package com.orderreporting;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface ExportMapper {
    @Insert("INSERT INTO export_log(actor_id,kind,filters_json,row_count) VALUES(#{actor},#{kind},#{filters},#{rows})")
    void record(long actor,String kind,String filters,long rows);
    @Select("SELECT id,kind,row_count,created_at FROM export_log WHERE actor_id=#{actor} ORDER BY id DESC LIMIT 30")
    List<Map<String,Object>> recent(long actor);
}
