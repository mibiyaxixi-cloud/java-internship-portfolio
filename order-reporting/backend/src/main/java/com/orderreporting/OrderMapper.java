package com.orderreporting;

import org.apache.ibatis.annotations.*;
import com.orderreporting.Model.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {
    @Select("SELECT content_hash FROM orders WHERE store_id=#{store} AND external_id=#{external}")
    String hash(long store,String external);
    @Insert("INSERT INTO orders(store_id,external_id,status,ordered_at,paid_at,refunded_at,total_cents,content_hash,batch_id) VALUES(#{d.storeId},#{d.externalId},#{d.status},#{d.orderedAt},#{d.paidAt},#{d.refundedAt},#{d.totalCents},#{d.contentHash},#{batch})")
    void insert(Draft d,String batch);
    @Select("SELECT id FROM orders WHERE store_id=#{store} AND external_id=#{external}") long id(long store,String external);
    @Insert("<script>INSERT INTO order_items(order_id,line_no,product_id,quantity,unit_cents,line_cents) VALUES <foreach collection='items' item='i' separator=','>(#{orderId},#{i.lineNo},#{i.productId},#{i.quantity},#{i.unitCents},#{i.lineCents})</foreach></script>")
    void items(long orderId,List<Line> items);
    String FILTER=" FROM orders o JOIN stores s ON s.id=o.store_id WHERE 1=1 <if test='store != null'>AND o.store_id=#{store}</if> <if test='start != null'>AND o.ordered_at &gt;= #{start}</if> <if test='end != null'>AND o.ordered_at &lt; #{end}</if> <if test='status != null'>AND o.status=#{status}</if>";
    String COLUMNS="o.id,o.store_id,s.name store_name,o.external_id,o.status,o.ordered_at,o.paid_at,o.refunded_at,o.total_cents,o.batch_id";
    @Select("<script>SELECT "+COLUMNS+FILTER+" ORDER BY o.ordered_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<Order> list(Long store,LocalDateTime start,LocalDateTime end,String status,int size,int offset);
    @Select("<script>SELECT COUNT(*) "+FILTER+"</script>")
    long count(Long store,LocalDateTime start,LocalDateTime end,String status);
    @Select("<script>SELECT "+COLUMNS+" FROM orders o JOIN stores s ON s.id=o.store_id WHERE o.id=#{id} <if test='store != null'>AND o.store_id=#{store}</if></script>")
    Order detail(long id,Long store);
    @Select("SELECT i.line_no,p.code product_code,p.name product_name,i.quantity,i.unit_cents,i.line_cents FROM order_items i JOIN products p ON p.id=i.product_id WHERE i.order_id=#{id} ORDER BY i.line_no")
    List<Item> detailItems(long id);
}
