package com.orderreporting;

import com.orderreporting.Model.*;
import com.orderreporting.ReportModel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.profiles.active=it","spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME}","spring.datasource.password=${TEST_DB_PASSWORD}"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers=TestDatabaseGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportsMySqlIT {
    @Autowired ReportService reports; @Autowired ExportService exports; @Autowired ImportService imports;
    @Autowired OrderService orders; @Autowired CatalogMapper catalog; @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc; @Autowired PasswordEncoder encoder;
    Actor admin,shop,other; long s1,s2,s0,p1,p2; String c1,c2,c0,pc1,pc2,batch;
    String prefix="R"+UUID.randomUUID().toString().replace("-","").substring(0,10);
    LocalDate d1=LocalDate.parse("2096-09-30"),d2=d1.plusDays(1);

    @BeforeAll void fixture() throws Exception {
        c1=prefix+"S1";c2=prefix+"S2";c0=prefix+"S0";pc1=prefix+"P1";pc2=prefix+"P2";
        catalog.addStore(c1,"报表一店");catalog.addStore(c2,"报表二店");catalog.addStore(c0,"无事件店");
        catalog.addProduct(pc1,"茶饮");catalog.addProduct(pc2,"糕点");
        s1=find("stores",c1);s2=find("stores",c2);s0=find("stores",c0);p1=find("products",pc1);p2=find("products",pc2);
        String hash=encoder.encode("Report-test-only-password");
        catalog.addUser(prefix+"admin",hash,"ADMIN",null);catalog.addUser(prefix+"shop",hash,"STORE",s1);catalog.addUser(prefix+"other",hash,"STORE",s2);
        admin=actor(prefix+"admin");shop=actor(prefix+"shop");other=actor(prefix+"other");
        StringWriter out=new StringWriter();out.write(CsvValidator.HEADER+"\r\n");
        try(CSVPrinter csv=new CSVPrinter(out,CSVFormat.RFC4180)) {
            csv.printRecord(c1,"A","REFUNDED",d1+"T23:00:00",d1+"T23:59:59",d2+"T00:00:00","100",1,pc1,2,"30","60");
            csv.printRecord(c1,"A","REFUNDED",d1+"T23:00:00",d1+"T23:59:59",d2+"T00:00:00","100",2,pc2,1,"40","40");
            csv.printRecord(c1,"B","PAID",d2+"T00:00:00",d2+"T00:00:00","","60",1,pc1,2,"30","60");
            csv.printRecord(c2,"C","PAID",d1+"T22:00:00",d1+"T23:59:59","","40",1,pc2,1,"40","40");
            csv.printRecord(c1,"D","CANCELLED",d2+"T10:00:00","","","20",1,pc1,1,"20","20");
        }
        var b=imports.precheck(out.toString().getBytes(StandardCharsets.UTF_8),admin);
        assertThat(b.errors()).isEmpty();batch=b.id();assertThat(imports.confirm(batch,admin).insertedCount()).isEqualTo(4);
    }
    long find(String table,String code){return jdbc.queryForObject("SELECT id FROM "+table+" WHERE code=?",Long.class,code);}
    Actor actor(String name){var a=catalog.account(name);return new Actor(a.id(),a.username(),a.role(),a.storeId());}
    @AfterAll void clean(){
        jdbc.update("DELETE FROM export_log WHERE actor_id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE i FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.store_id IN (?,?)",s1,s2);
        jdbc.update("DELETE FROM orders WHERE store_id IN (?,?)",s1,s2);
        jdbc.update("DELETE FROM audit_log WHERE actor_id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM import_batches WHERE owner_id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?)",admin.id(),shop.id(),other.id());
        jdbc.update("DELETE FROM products WHERE id IN (?,?)",p1,p2);jdbc.update("DELETE FROM stores WHERE id IN (?,?,?)",s1,s2,s0);
    }
    List<CSVRecord> csv(byte[] bytes) throws Exception {
        String text=new String(bytes,StandardCharsets.UTF_8);assertThat(text).startsWith("\uFEFF");
        try(CSVParser p=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(new StringReader(text.substring(1)))) { return p.getRecords(); }
    }
    long cents(List<CSVRecord> rows,String key){return rows.stream().mapToLong(r->new java.math.BigDecimal(r.get(key)).movePointRight(2).longValueExact()).sum();}
    long lineSum(List<EventLine> rows,boolean sales){return rows.stream().mapToLong(r->sales?r.salesCents():r.refundCents()).sum();}

    @Test void handComputedOverviewPreservesPaymentHistoryAndRefundDay(){
        var all=reports.overview(admin,null,d1,d2);
        assertThat(all.totals()).isEqualTo(new Totals(20000,10000,10000,3,1));
        assertThat(all.daily()).containsExactly(new Day(d1,14000,0,14000,2,0),new Day(d2,6000,10000,-4000,1,1));
        assertThat(all.stores()).anyMatch(s->s.storeId()==s1&&s.salesCents()==16000&&s.refundCents()==10000&&s.netCents()==6000);
        assertThat(all.stores()).anyMatch(s->s.storeId()==s2&&s.salesCents()==4000&&s.refundCents()==0);
        assertThat(all.stores()).anyMatch(s->s.storeId()==s0&&s.salesCents()==0&&s.netCents()==0);
        assertThat(all.products()).extracting(Product::productId).containsExactly(p1,p2);
        assertThat(all.products().getFirst()).isEqualTo(new Product(p1,pc1,"茶饮",4,2,12000,6000,6000));
        assertThat(all.products().get(1)).isEqualTo(new Product(p2,pc2,"糕点",2,1,8000,4000,4000));
        assertThat(reports.overview(shop,null,d1,d1).totals()).isEqualTo(new Totals(10000,0,10000,1,0));
    }
    @Test void summaryDailyStoresProductsDrilldownAndCsvReconcile() throws Exception {
        var r=reports.overview(admin,null,d1,d2);var totals=r.totals();
        assertThat(r.daily().stream().mapToLong(Day::netCents).sum()).isEqualTo(totals.netCents());
        assertThat(r.stores().stream().mapToLong(Store::salesCents).sum()).isEqualTo(totals.salesCents());
        assertThat(r.products().stream().mapToLong(Product::refundCents).sum()).isEqualTo(totals.refundCents());
        var items=reports.lines(admin,null,d1,d2,null,null,1,100);
        assertThat(items.total()).isEqualTo(6);assertThat(lineSum(items.items(),true)).isEqualTo(20000);assertThat(lineSum(items.items(),false)).isEqualTo(10000);
        var exported=csv(exports.lines(admin,null,d1,d2,null,null));
        assertThat(exported).hasSize(6);assertThat(cents(exported,"sales_yuan")).isEqualTo(20000);assertThat(cents(exported,"refund_yuan")).isEqualTo(10000);assertThat(cents(exported,"net_yuan")).isEqualTo(10000);
        for(int i=0;i<items.items().size();i++) {
            var item=items.items().get(i);var row=exported.get(i);
            assertThat(row.get("order_id")).isEqualTo(Long.toString(item.orderId()));assertThat(row.get("line_no")).isEqualTo(Integer.toString(item.lineNo()));assertThat(row.get("event_type")).isEqualTo(item.eventType());
        }
        var a=items.items().stream().filter(x->x.externalId().equals("A")).findFirst().orElseThrow();
        assertThat(orders.detail(shop,a.orderId()).items().stream().mapToLong(Item::lineCents).sum()).isEqualTo(10000);
    }
    @Test void singleDayAndRefundProductDrillDoNotPullOriginalPaymentDay() throws Exception {
        var page=reports.lines(shop,null,d2,d2,p2,"REFUND",1,20);
        assertThat(page.total()).isEqualTo(1);var line=page.items().getFirst();
        assertThat(line.eventAt()).isEqualTo(d2.atStartOfDay());assertThat(line.salesCents()).isZero();assertThat(line.refundCents()).isEqualTo(4000);assertThat(line.netCents()).isEqualTo(-4000);
        var exported=csv(exports.lines(shop,null,d2,d2,p2,"REFUND"));assertThat(exported).hasSize(1);assertThat(cents(exported,"net_yuan")).isEqualTo(-4000);
        assertThat(reports.lines(shop,null,d1,d1,p2,"REFUND",1,20).total()).isZero();
        assertThat(reports.lines(other,null,d1,d2,p1,null,1,20).total()).isZero();
    }
    @Test void zeroDaysLeapDayAndCancelledOnlyRangesRemainZero() throws Exception {
        var empty=reports.overview(shop,null,LocalDate.parse("2096-02-28"),LocalDate.parse("2096-03-01"));
        assertThat(empty.daily()).hasSize(3);assertThat(empty.daily().get(1).day()).isEqualTo(LocalDate.parse("2096-02-29"));
        assertThat(empty.totals()).isEqualTo(new Totals(0,0,0,0,0));assertThat(empty.products()).isEmpty();
        assertThat(csv(exports.lines(shop,null,d1.minusDays(1),d1.minusDays(1),null,null))).isEmpty();
        assertThat(csv(exports.orders(shop,null,d2,d2,"CANCELLED"))).hasSize(1);
        assertThat(reports.lines(shop,null,d1,d2,null,null,1,100).items()).noneMatch(x->x.externalId().equals("D"));
    }
    @Test void orderLedgerCsvUsesOrderedDatesStateAndSameScope() throws Exception {
        var page=orders.list(shop,null,d1,d1,"REFUNDED",1,20);assertThat(page.total()).isEqualTo(1);
        var rows=csv(exports.orders(shop,null,d1,d1,"REFUNDED"));assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("order_id")).isEqualTo(Long.toString(page.items().getFirst().id()));
        assertThat(rows.getFirst().get("status")).isEqualTo("REFUNDED");assertThat(cents(rows,"original_total_yuan")).isEqualTo(10000);
        assertThat(csv(exports.orders(other,null,d1,d2,null))).hasSize(1);
    }
    @Test void pageBoundariesAreDeterministicEvenForSameTimestamp(){
        var all=reports.lines(admin,null,d1,d2,null,null,1,100).items();var paged=new ArrayList<EventLine>();
        for(int i=1;i<=3;i++)paged.addAll(reports.lines(admin,null,d1,d2,null,null,i,2).items());
        assertThat(paged).containsExactlyElementsOf(all);assertThat(new HashSet<>(paged)).hasSize(6);
    }
    @Test void authenticationScopeAndExportCannotBeForged() throws Exception {
        String range="?from="+d1+"&to="+d2;
        for(String path:List.of("/api/reports","/api/reports/lines","/api/reports/lines/export","/api/orders/export")) {
            mvc.perform(get(path+range)).andExpect(status().isUnauthorized());
            mvc.perform(get(path+range+"&storeId="+s2).with(user(shop.username()).roles("ADMIN")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/reports"+range).with(user(shop.username())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.stores.length()").value(1)).andExpect(jsonPath("$.totals.salesCents").value(16000));
        mvc.perform(get("/api/reports/lines/export"+range).with(user(other.username())))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=report-event-lines.csv"))
            .andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void invalidRangesProductAndEventFiltersAreExplicitErrors() throws Exception {
        for(String query:List.of("", "?from=bad&to="+d2, "?from="+d2+"&to="+d1,
            "?from=2096-01-01&to=2097-01-01", "?from=1999-12-31&to=2000-01-01"))
            mvc.perform(get("/api/reports"+query).with(user(shop.username()))).andExpect(status().isBadRequest());
        for(String suffix:List.of("&productId=-1","&productId=9223372036854775807","&eventType=PAID","&page=0","&size=101"))
            mvc.perform(get("/api/reports/lines?from="+d1+"&to="+d2+suffix).with(user(shop.username()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/lines/export?from="+d1+"&to="+d2+"&eventType=CANCELLED").with(user(shop.username())))
            .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Content-Disposition"));
    }
    @Test void exportAuditIsLimitedToCurrentAccount() throws Exception {
        exports.lines(shop,null,d1,d2,null,null);
        mvc.perform(get("/api/exports").with(user(shop.username()))).andExpect(status().isOk()).andExpect(jsonPath("$[0].kind").value("EVENT_LINES"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_log WHERE actor_id=? AND row_count=5",Integer.class,shop.id())).isPositive();
        mvc.perform(get("/api/exports")).andExpect(status().isUnauthorized());
    }
    @Test void formulaTextAndDelimiterEscapingInBothCsvTypes() throws Exception {
        String dangerous="=SUM(1,2)\";@X-"+prefix;String day="2097-01-03";
        StringWriter out=new StringWriter();out.write(CsvValidator.HEADER+"\r\n");
        try(var printer=new CSVPrinter(out,CSVFormat.RFC4180)){printer.printRecord(c1,dangerous,"PAID",day+"T00:00:00",day+"T00:00:00","","1",1,pc1,1,"1","1");}
        var checked=imports.precheck(out.toString().getBytes(StandardCharsets.UTF_8),shop);assertThat(checked.errors()).isEmpty();imports.confirm(checked.id(),shop);
        LocalDate date=LocalDate.parse(day);
        for(byte[] bytes:List.of(exports.orders(shop,null,date,date,null),exports.lines(shop,null,date,date,null,null))) {
            var rows=csv(bytes);assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("order_no")).isEqualTo("'"+dangerous);
        }
    }
    @Test void multiPageExportsMatchAndTenThousandIsHardLimit() throws Exception {
        // Export boundary fixture, not M3 performance data. No cleanup of existing rows.
        LocalDate date=LocalDate.parse("2098-01-01");String bulk=prefix+"BULK-";
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String numbers="SELECT a.n+10*b.n+100*c.n+1000*d.n n FROM "+digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits+" d";
        jdbc.update("INSERT INTO orders(store_id,external_id,status,ordered_at,paid_at,total_cents,content_hash,batch_id) SELECT ?,CONCAT(?,n),'PAID',?,?,1,REPEAT('a',64),? FROM ("+numbers+") seq",s1,bulk,date.atStartOfDay(),date.atStartOfDay(),batch);
        jdbc.update("INSERT INTO order_items(order_id,line_no,product_id,quantity,unit_cents,line_cents) SELECT id,1,?,1,1,1 FROM orders WHERE store_id=? AND ordered_at=?",p1,s1,date.atStartOfDay());
        try {
            var ledger=csv(exports.orders(shop,null,date,date,null));var events=csv(exports.lines(shop,null,date,date,null,null));
            assertThat(ledger).hasSize(10000);assertThat(events).hasSize(10000);assertThat(cents(events,"sales_yuan")).isEqualTo(10000);
            assertThat(events.stream().map(r->r.get("order_id")).distinct().count()).isEqualTo(10000);
            assertThat(ledger.stream().map(r->r.get("order_id")).toList()).containsExactlyElementsOf(events.stream().map(r->r.get("order_id")).toList());
            jdbc.update("INSERT INTO orders(store_id,external_id,status,ordered_at,paid_at,total_cents,content_hash,batch_id) VALUES(?,?,'PAID',?,?,1,REPEAT('b',64),?)",s1,bulk+"extra",date.atStartOfDay(),date.atStartOfDay(),batch);
            jdbc.update("INSERT INTO order_items(order_id,line_no,product_id,quantity,unit_cents,line_cents) SELECT id,1,?,1,1,1 FROM orders WHERE store_id=? AND external_id=?",p1,s1,bulk+"extra");
            for(String path:List.of("/api/orders/export","/api/reports/lines/export"))
                mvc.perform(get(path+"?from="+date+"&to="+date).with(user(shop.username())))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_LIMIT")).andExpect(header().doesNotExist("Content-Disposition"));
        } finally {
            jdbc.update("DELETE i FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.store_id=? AND o.ordered_at=?",s1,date.atStartOfDay());
            jdbc.update("DELETE FROM orders WHERE store_id=? AND ordered_at=?",s1,date.atStartOfDay());
        }
    }

    @Test void replayAndDifferentFileWithSameOrdersLeaveReportsAndCsvUnchanged() throws Exception {
        var before=reports.overview(admin,null,d1,d2).totals();
        byte[] original=jdbc.queryForObject("SELECT content FROM import_batches WHERE id=?",byte[].class,batch);
        byte[] csvBefore=exports.lines(admin,null,d1,d2,null,null);
        assertThat(imports.precheck(original,admin).id()).isEqualTo(batch);
        assertThat(imports.confirm(batch,admin).insertedCount()).isEqualTo(4);
        var records=new ArrayList<>(CSVFormat.RFC4180.parse(new StringReader(new String(original,StandardCharsets.UTF_8))).getRecords());
        var header=records.removeFirst();Collections.reverse(records);StringWriter out=new StringWriter();
        try(var printer=new CSVPrinter(out,CSVFormat.RFC4180)){printer.printRecord(header);for(var r:records)printer.printRecord(r);}
        var checked=imports.precheck(out.toString().getBytes(StandardCharsets.UTF_8),admin);
        assertThat(checked.id()).isNotEqualTo(batch);
        var result=imports.confirm(checked.id(),admin);assertThat(result.insertedCount()).isZero();assertThat(result.skippedCount()).isEqualTo(4);
        assertThat(reports.overview(admin,null,d1,d2).totals()).isEqualTo(before);
        assertThat(exports.lines(admin,null,d1,d2,null,null)).isEqualTo(csvBefore);
    }

    @Test void reassignedAccountUsesCurrentScopeAtConfirmReportAndExport() throws Exception {
        String external="SCOPE-"+prefix;
        String source=CsvValidator.HEADER+"\r\n"+c1+","+external+",PAID,2099-01-01T00:00:00,2099-01-01T00:00:00,,1,1,"+pc1+",1,1,1\r\n";
        var checked=imports.precheck(source.getBytes(StandardCharsets.UTF_8),shop);assertThat(checked.status()).isEqualTo("READY");
        jdbc.update("UPDATE users SET store_id=? WHERE id=?",s2,shop.id());
        try {
            mvc.perform(post("/api/imports/"+checked.id()+"/confirm").with(user(shop.username()).roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE external_id=? AND store_id=?",Long.class,external,s1)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM import_batches WHERE id=?",String.class,checked.id())).isEqualTo("FAILED");
            String range="?from="+d1+"&to="+d2;
            mvc.perform(get("/api/reports"+range).with(user(shop.username()).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stores[0].storeId").value(s2)).andExpect(jsonPath("$.totals.salesCents").value(4000));
            mvc.perform(get("/api/orders/export"+range+"&storeId="+s1).with(user(shop.username())))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Content-Disposition"));
            var response=mvc.perform(get("/api/reports/lines/export"+range).with(user(shop.username())))
                .andExpect(status().isOk()).andReturn().getResponse();
            var rows=csv(response.getContentAsByteArray());assertThat(rows).hasSize(1);assertThat(rows.getFirst().get("order_no")).isEqualTo("C");
        } finally {jdbc.update("UPDATE users SET store_id=? WHERE id=?",s1,shop.id());}
    }

    @Test void auditWriteFailureDoesNotReturnCsvAttachment() throws Exception {
        String name=prefix+"auditfail",constraint="ck_export_"+prefix;
        catalog.addUser(name,encoder.encode("Audit-fault-only-password"),"STORE",s1);long accountId=actor(name).id();boolean installed=false;
        try {
            jdbc.execute("ALTER TABLE export_log ADD CONSTRAINT "+constraint+" CHECK(actor_id<>"+accountId+")");installed=true;
            mvc.perform(get("/api/reports/lines/export?from="+d1+"&to="+d2).with(user(name)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Content-Disposition"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_log WHERE actor_id=?",Long.class,accountId)).isZero();
        } finally {
            if(installed)jdbc.execute("ALTER TABLE export_log DROP CHECK "+constraint);
            jdbc.update("DELETE FROM export_log WHERE actor_id=?",accountId);jdbc.update("DELETE FROM users WHERE id=?",accountId);
        }
    }
}
