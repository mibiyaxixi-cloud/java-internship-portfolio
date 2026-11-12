package com.orderreporting;
import org.apache.ibatis.annotations.*;
import com.orderreporting.Model.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface BatchMapper {
    @Select("SELECT * FROM import_batches WHERE id=#{id}") Batch get(String id);
    @Select("SELECT * FROM import_batches WHERE owner_id=#{owner} AND file_hash=#{hash}") Batch byHash(long owner,String hash);
    @Select("SELECT * FROM import_batches WHERE id=#{id} FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE, useCache=false)
    Batch lock(String id);
    @Insert("INSERT INTO import_batches(id,owner_id,file_hash,content,status,row_count,order_count,errors_json) VALUES(#{id},#{ownerId},#{fileHash},#{content},#{status},#{rowCount},#{orderCount},#{errorsJson})")
    void insert(Batch b);
    @Update("UPDATE import_batches SET status=#{status},inserted_count=#{inserted},skipped_count=#{skipped},errors_json=#{errors},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id}")
    void result(String id,String status,int inserted,int skipped,String errors);
    @Insert("INSERT INTO audit_log(actor_id,batch_id,action,detail) VALUES(#{actor},#{batch},#{action},#{detail})")
    void audit(long actor,String batch,String action,String detail);
    @Select("SELECT id,owner_id,file_hash,status,row_count,order_count,inserted_count,skipped_count,errors_json,created_at,updated_at FROM import_batches WHERE owner_id=#{actor} ORDER BY created_at DESC,id DESC LIMIT 30")
    List<Batch> recent(long actor);
    @Select("SELECT action,detail,created_at FROM audit_log WHERE batch_id=#{batch} ORDER BY id")
    List<Map<String,Object>> auditFor(String batch);
}
