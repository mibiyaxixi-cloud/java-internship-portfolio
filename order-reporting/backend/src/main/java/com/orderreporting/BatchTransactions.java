package com.orderreporting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.orderreporting.Model.*;

/** Separate Spring bean: failure records commit after the business transaction rolls back. */
@Service
public class BatchTransactions {
    private final BatchMapper db;
    public BatchTransactions(BatchMapper db){this.db=db;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public Batch create(Batch b){
        db.insert(b);db.audit(b.ownerId,b.id,"PRECHECK",b.status);return db.get(b.id);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,timeout=10)
    public void failed(String id,long actor,String errors){
        Batch b=db.lock(id);
        // A racing request may already have committed. Never overwrite that success.
        if(b!=null && !b.status.equals("SUCCEEDED")){
            db.result(id,"FAILED",0,0,errors);db.audit(actor,id,"IMPORT_FAILED","业务事务已回滚；详情见批次错误");
        }
    }
}
