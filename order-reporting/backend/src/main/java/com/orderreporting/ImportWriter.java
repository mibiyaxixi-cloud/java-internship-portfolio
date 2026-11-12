package com.orderreporting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.orderreporting.Model.*;

@Service
public class ImportWriter {
    private final BatchMapper batches;private final OrderMapper orders;private final CsvValidator validator;
    public ImportWriter(BatchMapper batches,OrderMapper orders,CsvValidator validator){this.batches=batches;this.orders=orders;this.validator=validator;}
    @Transactional(timeout=30,rollbackFor=Exception.class)
    public Batch confirm(String id,Actor actor){
        Batch b=batches.lock(id);
        if(b==null || b.ownerId!=actor.id())throw new Problem(404,"BATCH_NOT_FOUND","批次不存在或不属于当前账号");
        // Same file + same owner has one batch; this lock serializes repeated confirmations.
        if(b.status.equals("SUCCEEDED"))return b;
        Check c=validator.validate(b.content,actor);
        if(!c.errors().isEmpty())throw new Problem(c.forbidden()?403:422,"VALIDATION_FAILED","重新校验未通过，未写入任何订单",c.errors());
        int inserted=0,skipped=0;
        for(Draft d:c.orders()){
            String old=orders.hash(d.storeId(),d.externalId());
            if(old!=null){
                if(!old.equals(d.contentHash()))throw new Problem(409,"ORDER_CONFLICT","同一订单已存在不同内容");
                skipped++;continue;
            }
            orders.insert(d,id);
            long orderId=orders.id(d.storeId(),d.externalId());
            orders.items(orderId,d.items());
            inserted++;
        }
        batches.result(id,"SUCCEEDED",inserted,skipped,"[]");
        batches.audit(actor.id(),id,"IMPORT_SUCCEEDED","新增 "+inserted+" 单；跳过相同内容 "+skipped+" 单");
        return batches.get(id);
    }
}
