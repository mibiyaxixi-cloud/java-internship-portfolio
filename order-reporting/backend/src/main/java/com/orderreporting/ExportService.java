package com.orderreporting;

import com.orderreporting.Model.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import static com.orderreporting.CsvExport.*;

@Service
public class ExportService {
    private final OrderService orders;
    private final OrderMapper orderDb;
    private final ReportService reports;
    private final ReportMapper reportDb;
    private final ExportAudit audit;
    public ExportService(OrderService orders,OrderMapper orderDb,ReportService reports,ReportMapper reportDb,ExportAudit audit) {
        this.orders=orders; this.orderDb=orderDb; this.reports=reports; this.reportDb=reportDb; this.audit=audit;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public byte[] orders(Actor actor,Long store,LocalDate from,LocalDate to,String status) {
        var f=orders.filter(actor,store,from,to,status);
        long count=orderDb.count(f.store(),f.start(),f.end(),f.status());
        checkCount(count);
        try(var csv=new CsvExport("order_id","order_no","store_id","store_name","status","ordered_at","paid_at","refunded_at","original_total_yuan")) {
            for(int offset=0; offset<count; offset+=PAGE_SIZE) {
                for(var o:orderDb.list(f.store(),f.start(),f.end(),f.status(),PAGE_SIZE,offset))
                    csv.row(o.id(),text(o.externalId()),o.storeId(),text(o.storeName()),o.status(),time(o.orderedAt()),time(o.paidAt()),time(o.refundedAt()),money(o.totalCents()));
            }
            byte[] result=csv.finish();
            audit.generated(actor.id(),"ORDER_LEDGER",f,count);
            return result;
        }
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public byte[] lines(Actor actor,Long store,LocalDate from,LocalDate to,Long product,String eventType) {
        var f=reports.filter(actor,store,from,to,product,eventType);
        long count=reportDb.lineCount(f);
        checkCount(count);
        try(var csv=new CsvExport("event_at","event_type","order_id","order_no","current_status","store_id","store_code","store_name",
            "line_no","product_id","product_code","product_name","event_quantity","sales_yuan","refund_yuan","net_yuan")) {
            for(int offset=0; offset<count; offset+=PAGE_SIZE) {
                for(var r:reportDb.lines(f,PAGE_SIZE,offset))
                    csv.row(time(r.eventAt()),r.eventType(),r.orderId(),text(r.externalId()),r.status(),r.storeId(),text(r.storeCode()),text(r.storeName()),
                        r.lineNo(),r.productId(),text(r.productCode()),text(r.productName()),r.quantity(),money(r.salesCents()),money(r.refundCents()),money(r.netCents()));
            }
            byte[] result=csv.finish();
            audit.generated(actor.id(),"EVENT_LINES",f,count);
            return result;
        }
    }
}
