package com.orderreporting;
import com.orderreporting.Model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Service
public class OrderService {
    private final Auth auth;private final OrderMapper db;
    public OrderService(Auth auth,OrderMapper db){this.auth=auth;this.db=db;}
    public record Filter(Long store,LocalDateTime start,LocalDateTime end,String status) {}
    public Filter filter(Actor actor,Long requested,LocalDate from,LocalDate to,String status){
        Long scope=auth.scope(actor,requested);
        if(status!=null && !Set.of("PAID","CANCELLED","REFUNDED").contains(status))throw new Problem(400,"BAD_STATUS","状态不合法");
        if((from!=null && (from.getYear()<2000 || from.getYear()>2100)) || (to!=null && (to.getYear()<2000 || to.getYear()>2100)) || (from!=null && to!=null && from.isAfter(to)))throw new Problem(400,"BAD_DATE","日期须在 2000—2100 年内，开始不能晚于结束");
        var start=from==null?null:from.atStartOfDay();var end=to==null?null:to.plusDays(1).atStartOfDay();
        return new Filter(scope,start,end,status);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Page<Order> list(Actor actor,Long requested,LocalDate from,LocalDate to,String status,int page,int size){
        Filter f=filter(actor,requested,from,to,status);
        ReportService.page(page,size);
        return new Page<>(db.list(f.store(),f.start(),f.end(),f.status(),size,(page-1)*size),db.count(f.store(),f.start(),f.end(),f.status()),page,size);
    }
    @Transactional(readOnly=true)
    public Detail detail(Actor actor,long id){
        var order=db.detail(id,auth.scope(actor,null));
        if(order==null)throw new Problem(404,"ORDER_NOT_FOUND","订单不存在或不可访问");
        return new Detail(order,db.detailItems(id));
    }
}
