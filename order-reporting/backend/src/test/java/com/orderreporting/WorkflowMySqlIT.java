package com.orderreporting;

import com.orderreporting.Model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.profiles.active=it","spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME}","spring.datasource.password=${TEST_DB_PASSWORD}"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers=TestDatabaseGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowMySqlIT {
    @Autowired ImportService imports; @Autowired OrderService orders; @Autowired CatalogMapper catalog;
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired PasswordEncoder encoder;
    Actor admin,shop,other; long s1,s2; String code1,code2,product,adminName,shopName,otherName;
    final String prefix="IT"+UUID.randomUUID().toString().replace("-","").substring(0,10);
    @BeforeAll void seed(){
        code1=prefix+"A";code2=prefix+"B";product=prefix+"P";
        catalog.addStore(code1,"测试一店");catalog.addStore(code2,"测试二店");catalog.addProduct(product,"测试商品");
        s1=jdbc.queryForObject("SELECT id FROM stores WHERE code=?",Long.class,code1);s2=jdbc.queryForObject("SELECT id FROM stores WHERE code=?",Long.class,code2);
        adminName=prefix+"admin";shopName=prefix+"shop";otherName=prefix+"other";
        String hash=encoder.encode("Test-only-password-2026");
        catalog.addUser(adminName,hash,"ADMIN",null);catalog.addUser(shopName,hash,"STORE",s1);catalog.addUser(otherName,hash,"STORE",s2);
        admin=actor(adminName);shop=actor(shopName);other=actor(otherName);
    }
    Actor actor(String name){var a=catalog.account(name);return new Actor(a.id(),a.username(),a.role(),a.storeId());}
    @AfterAll void cleanup(){
        // Only this run's fixture ids; no TRUNCATE, Flyway clean, or unscoped delete.
        jdbc.update("DELETE i FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.store_id IN (?,?)",s1,s2);
        jdbc.update("DELETE FROM orders WHERE store_id IN (?,?)",s1,s2);
        jdbc.update("DELETE FROM audit_log WHERE actor_id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM import_batches WHERE owner_id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM products WHERE code=?",product);jdbc.update("DELETE FROM stores WHERE id IN (?,?)",s1,s2);
    }
    String id(){return UUID.randomUUID().toString();}
    String row(String code,String id,String status,String ordered,String paid,String refunded,String total,int line,String qty,String unit,String amount){
        String escaped="\""+id.replace("\"","\"\"")+"\"";
        return String.join(",",code,escaped,status,ordered,paid,refunded,total,Integer.toString(line),product,qty,unit,amount);
    }
    String paid(String id,String amount){return row(code1,id,"PAID","2026-10-01T09:00:00","2026-10-01T09:00:00","",amount,1,"1",amount,amount);}
    byte[] csv(String...rows){return (CsvValidator.HEADER+"\r\n"+String.join("\r\n",rows)+"\r\n").getBytes(StandardCharsets.UTF_8);}
    BatchView upload(Actor a,String...rows){return imports.precheck(csv(rows),a);}
    BatchView commit(Actor a,String...rows){var b=upload(a,rows);assertThat(b.errors()).isEmpty();return imports.confirm(b.id(),a);}
    long count(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE store_id=? AND external_id=?",Long.class,s1,id);}

    @Test void quotedCsvAndExactCentsRoundTrip(){
        String id="订单,含\"引号\"-"+id();
        var b=commit(shop,row(code1,id,"PAID","2026-10-01T09:00:00","2026-10-01T09:01:00","","0.30",1,"2","0.10","0.20"),row(code1,id,"PAID","2026-10-01T09:00:00","2026-10-01T09:01:00","","0.30",2,"1","0.10","0.10"));
        assertThat(b.insertedCount()).isEqualTo(1);
        long oid=jdbc.queryForObject("SELECT id FROM orders WHERE store_id=? AND external_id=?",Long.class,s1,id);
        var d=orders.detail(shop,oid);assertThat(d.order().externalId()).isEqualTo(id);assertThat(d.order().totalCents()).isEqualTo(30);
        assertThat(d.items()).hasSize(2);assertThat(d.items().stream().mapToLong(Item::lineCents).sum()).isEqualTo(30);
    }
    @Test void invalidOneRowWritesNoBusinessData(){
        String first=id(),bad=id();
        var b=upload(shop,paid(first,"10.00"),paid(bad,"10.123"));
        assertThat(b.status()).isEqualTo("INVALID");assertThat(b.errors()).anyMatch(e->e.line()==3);
        assertThatThrownBy(()->imports.confirm(b.id(),shop)).isInstanceOf(Problem.class);
        assertThat(count(first)).isZero();assertThat(count(bad)).isZero();
        assertThat(imports.owned(b.id(),shop).status).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE batch_id=? AND action='IMPORT_FAILED'",Integer.class,b.id())).isEqualTo(1);
    }
    @Test void repeatedFileAndReorderedLinesAreIdempotent(){
        String id=id();String r1=row(code1,id,"PAID","2026-10-01T09:00:00","2026-10-01T09:00:00","","30",1,"1","10","10");String r2=r1.replace(",30,1,",",30,2,").replace(",1,10,10",",1,20,20");
        var b=commit(shop,r1,r2);var repeat=upload(shop,r1,r2);
        assertThat(repeat.id()).isEqualTo(b.id());assertThat(imports.confirm(b.id(),shop).insertedCount()).isEqualTo(1);
        var reordered=commit(shop,r2,r1);assertThat(reordered.insertedCount()).isZero();assertThat(reordered.skippedCount()).isEqualTo(1);assertThat(count(id)).isEqualTo(1);
    }
    @Test void existingOrderDifferentContentAndConfirmRevalidation(){
        String id=id();var a=upload(shop,paid(id,"10"));var b=upload(shop,paid(id,"11"));imports.confirm(b.id(),shop);
        assertThatThrownBy(()->imports.confirm(a.id(),shop)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(422));
        assertThat(upload(shop,paid(id,"12")).errors()).anyMatch(e->e.reason().contains("不同内容"));
        assertThat(jdbc.queryForObject("SELECT total_cents FROM orders WHERE store_id=? AND external_id=?",Long.class,s1,id)).isEqualTo(1100);
    }
    @Test void duplicateDetailAndMismatchedOrderTotalAreRejected(){
        String id=id();var b=upload(shop,paid(id,"10"),paid(id,"10"));assertThat(b.errors()).anyMatch(e->e.reason().contains("重复"));
        b=upload(shop,row(code1,id(),"PAID","2026-10-01T09:00:00","2026-10-01T09:00:00","","20",1,"1","10","10"));assertThat(b.errors()).anyMatch(e->e.reason().contains("合计"));
    }
    @Test void datesAmountsAndForeignKeysAreValidated(){
        assertThat(upload(shop,paid(id(),"10").replace("2026-10-01","2026-02-30")).errors()).isNotEmpty();
        assertThat(upload(shop,paid(id(),"10").replace(product,"NO_SUCH_PRODUCT")).errors()).anyMatch(e->e.reason().contains("商品"));
        assertThat(upload(shop,paid(id(),"10").replace(code1,"NO_SUCH_STORE")).errors()).anyMatch(e->e.reason().contains("门店"));
        assertThat(upload(shop,row(code1,id(),"REFUNDED","2026-10-01T09:00:00","2026-10-01T10:00:00","2026-10-01T09:59:59","10",1,"1","10","10")).errors()).isNotEmpty();
        assertThat(upload(shop,row(code1,id(),"CANCELLED","2026-10-01T09:00:00","2026-10-01T09:00:00","","10",1,"1","10","10")).errors()).isNotEmpty();
    }
    @Test void invalidEncodingHeaderQuotesAndRowLimit(){
        assertThat(imports.precheck(new byte[]{(byte)0xc3,0x28},shop).errors()).isNotEmpty();
        assertThat(imports.precheck("bad,header".getBytes(StandardCharsets.UTF_8),shop).errors()).anyMatch(e->e.line()==1);
        assertThat(imports.precheck((CsvValidator.HEADER+"\n\"unclosed").getBytes(StandardCharsets.UTF_8),shop).errors()).anyMatch(e->e.reason().contains("引号"));
        assertThatThrownBy(()->imports.precheck(new byte[CsvValidator.MAX_BYTES+1],shop)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(413));
    }
    @Test void fiveThousandRowsAcceptedButNextRecordRejected(){
        String order=id();String[] rows=new String[5001];
        for(int i=0;i<rows.length;i++)rows[i]=row(code1,order,"PAID","2026-10-01T09:00:00","2026-10-01T09:00:00","","5000",i+1,"1","1","1");
        var exact=upload(shop,Arrays.copyOf(rows,5000));assertThat(exact.status()).isEqualTo("READY");assertThat(exact.rowCount()).isEqualTo(5000);
        assertThat(upload(shop,rows).errors()).anyMatch(e->e.reason().contains("最多 5000"));assertThat(count(order)).isZero();
    }
    @Test void rowNumbersUsePhysicalStartLines(){
        var b=upload(shop,paid("a\nb","10"),paid(id(),"10.123"));
        assertThat(b.errors()).anyMatch(e->e.line()==2);assertThat(b.errors()).anyMatch(e->e.line()==4);
    }
    @Test void crossStoreImportAndBatchAccessDenied(){
        String id=id();byte[] file=csv(paid(id,"10"));
        assertThatThrownBy(()->imports.precheck(file,other)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(403));
        var b=imports.precheck(file,shop);
        assertThatThrownBy(()->imports.confirm(b.id(),other)).isInstanceOfSatisfying(Problem.class,e->assertThat(e.status).isEqualTo(404));
        assertThatThrownBy(()->imports.owned(b.id(),other)).isInstanceOf(Problem.class);assertThat(count(id)).isZero();
    }
    @Test void concurrentPrecheckAndConfirmDoNotDuplicate() throws Exception {
        String id=id();byte[] file=csv(paid(id,"10"));
        try(var pool=Executors.newFixedThreadPool(4)){
            var tasks=new ArrayList<Callable<BatchView>>();for(int i=0;i<4;i++)tasks.add(()->imports.precheck(file,shop));
            var results=pool.invokeAll(tasks);String batch=results.getFirst().get().id();for(var f:results)assertThat(f.get().id()).isEqualTo(batch);
            tasks.clear();for(int i=0;i<4;i++)tasks.add(()->imports.confirm(batch,shop));for(var f:pool.invokeAll(tasks))assertThat(f.get().status()).isEqualTo("SUCCEEDED");
        }
        assertThat(count(id)).isEqualTo(1);
    }
    @Test void differentFilesRacingOnSameOrderCannotDuplicate() throws Exception {
        String id=id();String row=paid(id,"10");var a=upload(shop,row);var b=imports.precheck((CsvValidator.HEADER+"\n"+row+"\n").getBytes(StandardCharsets.UTF_8),shop);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);List<Callable<Object>> tasks=new ArrayList<>();
            for(var batch:List.of(a,b))tasks.add(()->{gate.await();try{return imports.confirm(batch.id(),shop);}catch(Problem p){return p;}});
            var f1=pool.submit(tasks.get(0));var f2=pool.submit(tasks.get(1));gate.countDown();f1.get();f2.get();
        }
        assertThat(count(id)).isEqualTo(1);
        assertThat(imports.confirm(a.id(),shop).status()).isEqualTo("SUCCEEDED");assertThat(imports.confirm(b.id(),shop).status()).isEqualTo("SUCCEEDED");
    }
    @Test void realDatabaseFailureRollsBackFirstOrderAndKeepsFailureRecord(){
        String good=id(),bad="FAIL_"+id(),constraint="ck_"+prefix;
        var b=upload(shop,paid(good,"10"),paid(bad,"20"));
        jdbc.execute("ALTER TABLE orders ADD CONSTRAINT "+constraint+" CHECK (external_id <> '"+bad+"')");
        try{
            assertThatThrownBy(()->imports.confirm(b.id(),shop)).isInstanceOf(Problem.class);
            assertThat(count(good)).isZero();assertThat(count(bad)).isZero();assertThat(imports.owned(b.id(),shop).status).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.batch_id=?",Long.class,b.id())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE batch_id=? AND action='IMPORT_FAILED'",Long.class,b.id())).isEqualTo(1);
        }finally{jdbc.execute("ALTER TABLE orders DROP CHECK "+constraint);}
        assertThat(imports.confirm(b.id(),shop).insertedCount()).isEqualTo(2);
    }
    @Test void handCalculatedPaymentRefundEventsAndDayBoundaries(){
        String a=id(),b=id(),c=id(),d=id();
        var batch=commit(admin,
            row(code1,a,"REFUNDED","2026-09-30T23:00:00","2026-09-30T23:59:59","2026-10-01T00:00:00","100",1,"2","30","60"),
            row(code1,a,"REFUNDED","2026-09-30T23:00:00","2026-09-30T23:59:59","2026-10-01T00:00:00","100",2,"1","40","40"),
            row(code1,b,"PAID","2026-10-01T00:00:00","2026-10-01T00:00:00","","60",1,"2","30","60"),
            row(code2,c,"PAID","2026-09-30T22:00:00","2026-09-30T23:59:59","","40",1,"1","40","40"),
            row(code1,d,"CANCELLED","2026-10-01T10:00:00","","","20",1,"1","20","20"));
        // M1 validates retained events via SQL; production report endpoints belong to M2.
        assertThat(sum(batch.id(),null,"paid_at","2026-09-30")).isEqualTo(14000);
        assertThat(sum(batch.id(),s1,"paid_at","2026-09-30")).isEqualTo(10000);
        assertThat(sum(batch.id(),s2,"paid_at","2026-09-30")).isEqualTo(4000);
        assertThat(sum(batch.id(),null,"paid_at","2026-10-01")).isEqualTo(6000);
        assertThat(sum(batch.id(),null,"refunded_at","2026-09-30")).isZero();
        assertThat(sum(batch.id(),null,"refunded_at","2026-10-01")).isEqualTo(10000);
        long oid=jdbc.queryForObject("SELECT id FROM orders WHERE store_id=? AND external_id=?",Long.class,s1,a);
        assertThat(orders.detail(shop,oid).items().stream().mapToLong(Item::lineCents).sum()).isEqualTo(10000);
        var p=orders.list(shop,null,LocalDate.parse("2026-09-30"),LocalDate.parse("2026-09-30"),null,1,100);
        assertThat(p.items()).allMatch(o->o.storeId()==s1 && o.orderedAt().toLocalDate().equals(LocalDate.parse("2026-09-30")));
    }
    long sum(String batch,Long store,String column,String day){
        var start=LocalDate.parse(day).atStartOfDay();var end=start.plusDays(1);
        return store==null?jdbc.queryForObject("SELECT COALESCE(SUM(total_cents),0) FROM orders WHERE batch_id=? AND "+column+">=? AND "+column+"<?",Long.class,batch,start,end):jdbc.queryForObject("SELECT COALESCE(SUM(total_cents),0) FROM orders WHERE batch_id=? AND store_id=? AND "+column+">=? AND "+column+"<?",Long.class,batch,store,start,end);
    }
    @Test void httpAuthenticationCsrfAndLogin() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username",shopName).param("password","Test-only-password-2026")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",shopName).param("password","wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username",shopName).param("password","Test-only-password-2026")).andExpect(status().isNoContent());
    }
    @Test void httpServerScopePaginationAndDetailProtection() throws Exception {
        String id=id();commit(shop,paid(id,"10"));long oid=jdbc.queryForObject("SELECT id FROM orders WHERE store_id=? AND external_id=?",Long.class,s1,id);
        mvc.perform(get("/api/orders").with(user(shopName)).param("storeId",Long.toString(s2))).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/"+oid).with(user(otherName))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/"+oid).with(user(adminName))).andExpect(status().isOk()).andExpect(jsonPath("$.order.totalCents").value(1000));
        mvc.perform(get("/api/orders").with(user(shopName)).param("size","1")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].storeId").value(s1));
        mvc.perform(get("/api/orders").with(user(shopName)).param("page","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").with(user(shopName)).param("from","bad-date")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").with(user(shopName)).param("from","2026-10-02").param("to","2026-10-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").with(user(shopName)).param("status","UNKNOWN")).andExpect(status().isBadRequest());
    }
    @Test void httpMultipartForbiddenAndMissingFile() throws Exception {
        var file=new MockMultipartFile("file","orders.csv","text/csv",csv(paid(id(),"10")));
        mvc.perform(multipart("/api/imports/precheck").file(file).with(user(otherName)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/precheck").file(file).with(user(shopName))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/precheck").with(user(shopName)).with(csrf())).andExpect(status().isBadRequest());
    }
}
