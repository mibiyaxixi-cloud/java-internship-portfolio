package com.orderreporting;

import com.orderreporting.Model.Actor;
import com.orderreporting.Model.Page;
import com.orderreporting.ReportModel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReportService {
    private final Auth auth;
    private final ReportMapper db;
    private final CatalogMapper catalog;
    public ReportService(Auth auth, ReportMapper db, CatalogMapper catalog) {
        this.auth=auth; this.db=db; this.catalog=catalog;
    }

    public Filter filter(Actor actor, Long requested, LocalDate from, LocalDate to, Long product, String eventType) {
        Long scope=auth.scope(actor,requested);
        if(from==null || to==null || from.getYear()<2000 || to.getYear()>2100 || from.isAfter(to)
            || ChronoUnit.DAYS.between(from,to)>365)
            throw new Problem(400,"BAD_REPORT_RANGE","开始/结束日期必填，范围为 2000—2100 年，最多 366 个自然日");
        if(eventType!=null && !Set.of("PAYMENT","REFUND").contains(eventType))
            throw new Problem(400,"BAD_EVENT_TYPE","事件类型仅支持 PAYMENT 或 REFUND");
        if(product!=null && (product<1 || db.productExists(product)==0))
            throw new Problem(400,"BAD_PRODUCT","商品不存在");
        return new Filter(scope,from.atStartOfDay(),to.plusDays(1).atStartOfDay(),product,eventType);
    }

    public static void page(int page,int size) {
        if(page<1 || page>10000 || size<1 || size>100)
            throw new Problem(400,"BAD_PAGE","page 为 1—10000，size 为 1—100");
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Overview overview(Actor actor,Long store,LocalDate from,LocalDate to) {
        Filter f=filter(actor,store,from,to,null,null);
        Totals totals=db.totals(f);
        var byDay=db.daily(f).stream().collect(Collectors.toMap(Day::day,Function.identity()));
        var daily=new ArrayList<Day>();
        for(LocalDate d=from; !d.isAfter(to); d=d.plusDays(1))
            daily.add(byDay.getOrDefault(d,new Day(d,0,0,0,0,0)));
        var byStore=db.stores(f).stream().collect(Collectors.toMap(Store::storeId,Function.identity()));
        var stores=catalog.stores(f.store()).stream().map(s->byStore.getOrDefault(s.id(),new Store(s.id(),s.code(),s.name(),0,0,0,0,0)))
            .sorted(Comparator.comparingLong(Store::netCents).reversed().thenComparingLong(Store::storeId)).toList();
        return new Overview(from,to,"Asia/Shanghai","PAYMENT_AND_REFUND_EVENTS",totals,daily,stores,db.products(f),20);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Page<EventLine> lines(Actor actor,Long store,LocalDate from,LocalDate to,Long product,String eventType,int page,int size) {
        Filter f=filter(actor,store,from,to,product,eventType);
        page(page,size);
        return new Page<>(db.lines(f,size,(page-1)*size),db.lineCount(f),page,size);
    }
}
