package com.orderreporting;
import com.orderreporting.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import java.util.*;

@Service
public class ImportService {
    private final CsvValidator validator;private final BatchMapper db;private final BatchTransactions records;private final ImportWriter writer;private final ObjectMapper json;
    public ImportService(CsvValidator validator,BatchMapper db,BatchTransactions records,ImportWriter writer,ObjectMapper json){this.validator=validator;this.db=db;this.records=records;this.writer=writer;this.json=json;}
    String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public BatchView view(Batch b){
        try{return new BatchView(b.id,b.status,b.rowCount,b.orderCount,b.insertedCount,b.skippedCount,json.readValue(b.errorsJson,new TypeReference<List<RowError>>(){}),b.createdAt);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public Batch owned(String id,Actor actor){
        Batch b=db.get(id);
        if(b==null || b.ownerId!=actor.id())throw new Problem(404,"BATCH_NOT_FOUND","批次不存在或不属于当前账号");
        return b;
    }
    public BatchView precheck(byte[] bytes,Actor actor){
        if(bytes.length>CsvValidator.MAX_BYTES)throw new Problem(413,"FILE_TOO_LARGE","文件不得超过 1 MiB");
        String hash=CsvValidator.hash(bytes);
        Batch old=db.byHash(actor.id(),hash);
        if(old!=null){
            // Recheck scope even for replay: a changed account assignment cannot bypass authorization.
            Check c=validator.validate(bytes,actor);
            if(c.forbidden())throw new Problem(403,"FORBIDDEN","文件含其他门店数据",view(old));
            return view(old);
        }
        Check c=validator.validate(bytes,actor);
        Batch b=new Batch();b.id=UUID.randomUUID().toString();b.ownerId=actor.id();b.fileHash=hash;b.content=bytes;
        b.status=c.errors().isEmpty()?"READY":"INVALID";b.rowCount=c.rowCount();b.orderCount=c.orders().size();b.errorsJson=json(c.errors());
        try{b=records.create(b);}catch(DuplicateKeyException e){b=db.byHash(actor.id(),hash);if(b==null)throw e;}
        BatchView v=view(b);
        if(c.forbidden())throw new Problem(403,"FORBIDDEN","文件含其他门店数据，整批拒绝",v);
        return v;
    }
    public BatchView confirm(String id,Actor actor){
        owned(id,actor);
        try{return view(writer.confirm(id,actor));}
        catch(Problem e){
            String errors=json(e.detail instanceof List<?> ? e.detail : List.of(new RowError(0,e.getMessage())));
            records.failed(id,actor.id(),errors);throw e;
        }catch(RuntimeException e){
            // SQL details stay in server logs; do not expose schema or values in the API.
            org.slf4j.LoggerFactory.getLogger(ImportService.class).warn("Import transaction failed for batch {}",id,e);
            String message="导入事务失败；本批业务已回滚，可重试确认；持续失败请联系管理员";
            try{records.failed(id,actor.id(),json(List.of(new RowError(0,message))));}
            catch(RuntimeException recordError){
                throw new Problem(503,"FAILURE_RECORD_UNAVAILABLE","数据库不可用，未能持久化失败状态；恢复后查询批次并重试");
            }
            throw new Problem(e instanceof DuplicateKeyException?409:503,"IMPORT_FAILED",message);
        }
    }
    public List<BatchView> recent(Actor actor){return db.recent(actor.id()).stream().map(this::view).toList();}
}
