package com.orderreporting;

import org.apache.ibatis.annotations.*;
import com.orderreporting.ReportModel.*;
import java.util.List;

@Mapper
public interface ReportMapper {
    // Shared by aggregates, drilldown, count and CSV. Dates are [start,end).
    String EVENT_FILTER = " e.event_at &gt;= #{f.start} AND e.event_at &lt; #{f.end} "
        + "<if test='f.store != null'> AND e.store_id=#{f.store}</if> "
        + "<if test='f.eventType != null'> AND e.event_type=#{f.eventType}</if> ";
    String TOTALS = "COALESCE(SUM(e.sales_cents),0) sales_cents, COALESCE(SUM(e.refund_cents),0) refund_cents, "
        + "COALESCE(SUM(e.sales_cents-e.refund_cents),0) net_cents, "
        + "COALESCE(SUM(e.event_type='PAYMENT'),0) paid_orders, COALESCE(SUM(e.event_type='REFUND'),0) refunded_orders";
    String LINE_FROM = " FROM order_events e JOIN orders o ON o.id=e.order_id JOIN stores s ON s.id=e.store_id "
        + "JOIN order_items i ON i.order_id=e.order_id JOIN products p ON p.id=i.product_id WHERE "
        + EVENT_FILTER + "<if test='f.product != null'> AND i.product_id=#{f.product}</if> ";
    String LINE_MONEY = "IF(e.event_type='PAYMENT',i.line_cents,0) sales_cents, "
        + "IF(e.event_type='REFUND',i.line_cents,0) refund_cents, IF(e.event_type='PAYMENT',i.line_cents,-i.line_cents) net_cents";

    @Select("<script>SELECT " + TOTALS + " FROM order_events e WHERE " + EVENT_FILTER + "</script>")
    Totals totals(@Param("f") Filter filter);

    @Select("<script>SELECT DATE(e.event_at) day," + TOTALS + " FROM order_events e WHERE " + EVENT_FILTER
        + " GROUP BY DATE(e.event_at) ORDER BY day</script>")
    List<Day> daily(@Param("f") Filter filter);

    @Select("<script>SELECT e.store_id,s.code store_code,s.name store_name," + TOTALS
        + " FROM order_events e JOIN stores s ON s.id=e.store_id WHERE " + EVENT_FILTER
        + " GROUP BY e.store_id,s.code,s.name ORDER BY net_cents DESC,e.store_id</script>")
    List<Store> stores(@Param("f") Filter filter);

    // At product granularity, aggregate line amounts, never e.sales_cents per joined item.
    @Select("<script>SELECT p.id product_id,p.code product_code,p.name product_name, "
        + "SUM(IF(e.event_type='PAYMENT',i.quantity,0)) sold_quantity, "
        + "SUM(IF(e.event_type='REFUND',i.quantity,0)) refunded_quantity, "
        + "SUM(IF(e.event_type='PAYMENT',i.line_cents,0)) sales_cents, "
        + "SUM(IF(e.event_type='REFUND',i.line_cents,0)) refund_cents, "
        + "SUM(IF(e.event_type='PAYMENT',i.line_cents,-i.line_cents)) net_cents " + LINE_FROM
        + " GROUP BY p.id,p.code,p.name ORDER BY net_cents DESC,p.id LIMIT 20</script>")
    List<Product> products(@Param("f") Filter filter);

    @Select("<script>SELECT e.event_at,e.event_type,o.id order_id,o.external_id,o.status,e.store_id,"
        + "s.code store_code,s.name store_name,i.line_no,p.id product_id,p.code product_code,p.name product_name,i.quantity,"
        + LINE_MONEY + LINE_FROM + "ORDER BY e.event_at DESC,e.order_id DESC,e.event_type ASC,i.line_no ASC LIMIT #{size} OFFSET #{offset}</script>")
    List<EventLine> lines(@Param("f") Filter filter, int size, int offset);

    @Select("<script>SELECT COUNT(*) " + LINE_FROM + "</script>")
    long lineCount(@Param("f") Filter filter);

    @Select("SELECT COUNT(*) FROM products WHERE id=#{id}")
    long productExists(long id);
}
