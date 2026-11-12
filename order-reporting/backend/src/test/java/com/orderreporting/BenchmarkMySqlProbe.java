package com.orderreporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderreporting.Model.*;
import org.apache.commons.csv.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit scripts/benchmark.sh only. Imports deterministic bounded CSV batches into the test DB. */
@SpringBootTest(properties={"spring.profiles.active=it","spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME}","spring.datasource.password=${TEST_DB_PASSWORD}"})
@ContextConfiguration(initializers=TestDatabaseGuard.class)
class BenchmarkMySqlProbe {
    @Autowired JdbcTemplate jdbc; @Autowired CatalogMapper catalog; @Autowired ImportService imports;
    @Autowired OrderMapper orders; @Autowired SqlSessionFactory sessions; @Autowired ObjectMapper json;
    static final String PREFIX="BENCH_M3_", INDEX="ix_store_status_ordered_id";
    final List<Long> storeIds=new ArrayList<>(), productIds=new ArrayList<>();
    Long owner; boolean createdIndex;
    Path output;
    record Query(String sql,List<Object> parameters) {}

    @Test void measureRealOrderQuery() throws Exception {
        output=Path.of(System.getProperty("benchmark.output"));Files.createDirectory(output);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("order_reporting_test");
        // Refuse collisions; never delete a prior run or unrelated rows to make the benchmark succeed.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE LEFT(code,9)=?",Long.class,PREFIX)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE LEFT(code,9)=?",Long.class,PREFIX)).isZero();
        assertThat(catalog.account(PREFIX+"actor")).isNull();
        var initialCounts=counts();var result=new LinkedHashMap<String,Object>();
        result.put("startedAt",OffsetDateTime.now().toString());result.put("initialTableCounts",initialCounts);
        result.put("environment",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name")+" "+System.getProperty("os.version"),
            "arch",System.getProperty("os.arch"),"logicalProcessors",Runtime.getRuntime().availableProcessors(),
            "mysql",jdbc.queryForMap("SELECT VERSION() version,@@port port,@@innodb_buffer_pool_size buffer_pool_bytes,@@transaction_isolation transaction_isolation,@@time_zone time_zone,DATABASE() db")));
        result.put("method","5 warmups then 20 samples; JDBC execute + all returned rows read, milliseconds; local single client, warm cache, no concurrency or HTTP timing");
        try {
            List<Path> files=generate();result.put("fixtureFiles",files.stream().map(p->{try{return Map.of("file",p.getFileName().toString(),"sha256",CsvValidator.hash(Files.readAllBytes(p)),"bytes",Files.size(p),"rows",5000);}catch(IOException e){throw new UncheckedIOException(e);}}).toList());
            for(int i=0;i<10;i++){String code=PREFIX+"S"+i;catalog.addStore(code,"基准门店 "+i);storeIds.add(id("stores",code));}
            for(int i=0;i<20;i++){String code=PREFIX+"P"+i;catalog.addProduct(code,"基准商品 "+i);productIds.add(id("products",code));}
            catalog.addUser(PREFIX+"actor","!benchmark-no-login","ADMIN",null);
            var account=catalog.account(PREFIX+"actor");owner=account.id();var actor=new Actor(owner,account.username(),"ADMIN",null);
            long loadStart=System.nanoTime();
            for(Path p:files){var checked=imports.precheck(Files.readAllBytes(p),actor);assertThat(checked.errors()).isEmpty();
                assertThat(imports.confirm(checked.id(),actor).insertedCount()).isEqualTo(2500);
                System.out.println("BENCH imported "+p.getFileName()+" / 5000 lines");}
            result.put("fixtureLoadMs",(System.nanoTime()-loadStart)/1e6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN import_batches b ON b.id=o.batch_id WHERE b.owner_id=?",Long.class,owner)).isEqualTo(50000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id=i.order_id JOIN import_batches b ON b.id=o.batch_id WHERE b.owner_id=?",Long.class,owner)).isEqualTo(100000);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT o.id FROM orders o JOIN import_batches b ON b.id=o.batch_id JOIN order_items i ON i.order_id=o.id WHERE b.owner_id=? GROUP BY o.id,o.total_cents HAVING COUNT(*)<>2 OR SUM(i.line_cents)<>o.total_cents) bad",Long.class,owner)).isZero();
            result.put("fixture",Map.of("orders",50000,"items",100000,"stores",10,"products",20,"csvFiles",20,"year",2085,
                "statusCounts",jdbc.queryForList("SELECT o.status,COUNT(*) orders FROM orders o JOIN import_batches b ON b.id=o.batch_id WHERE b.owner_id=? GROUP BY o.status",owner)));
            jdbc.execute("ANALYZE TABLE orders,order_items,stores");
            boolean existed=indexExists();
            if(existed)assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name=? ORDER BY SEQ_IN_INDEX",String.class,INDEX))
                .containsExactly("store_id","status","ordered_at","id");
            result.put("baselineMode",existed?"existing candidate excluded with IGNORE INDEX (reproduction after V3)":"actual schema before candidate DDL");
            var params=new HashMap<String,Object>();params.put("store",storeIds.getFirst());params.put("status","REFUNDED");
            params.put("start",LocalDateTime.parse("2085-01-01T00:00:00"));params.put("end",LocalDateTime.parse("2086-01-01T00:00:00"));params.put("size",20);params.put("offset",0);
            Query list=bound("list",params),count=bound("count",params);
            result.put("listQuery",list);result.put("countQuery",count);
            var expected=jdbc.queryForList(list.sql(),list.parameters().toArray());assertThat(expected).hasSize(20);
            assertThat(orders.list(storeIds.getFirst(),(LocalDateTime)params.get("start"),(LocalDateTime)params.get("end"),"REFUNDED",20,0).stream().map(Order::id).toList())
                .containsExactlyElementsOf(expected.stream().map(r->((Number)r.get("id")).longValue()).toList());
            result.put("matchingOrders",orders.count(storeIds.getFirst(),(LocalDateTime)params.get("start"),(LocalDateTime)params.get("end"),"REFUNDED"));
            Query beforeList=existed?withoutIndex(list):list,beforeCount=existed?withoutIndex(count):count;
            result.put("beforeList",measure(beforeList));result.put("beforeCount",measure(beforeCount));
            if(!existed){jdbc.execute("CREATE INDEX "+INDEX+" ON orders(store_id,status,ordered_at,id)");createdIndex=true;}
            jdbc.execute("ANALYZE TABLE orders");
            result.put("afterList",measure(list));result.put("afterCount",measure(count));
            assertThat(jdbc.queryForList(list.sql(),list.parameters().toArray())).isEqualTo(expected);
            // Alternate paths after both are warm, to expose run-order/JIT/cache bias in sequential timings.
            List<Double> oldTimes=new ArrayList<>(),newTimes=new ArrayList<>();Query old=withoutIndex(list);
            for(int i=0;i<5;i++){execute(old);execute(list);}
            for(int i=0;i<20;i++){if(i%2==0){oldTimes.add(execute(old));newTimes.add(execute(list));}else{newTimes.add(execute(list));oldTimes.add(execute(old));}}
            result.put("alternatingListBaseline",stats(oldTimes));result.put("alternatingListCandidate",stats(newTimes));
            result.put("listResultsIdentical",true);result.put("finishedAt",OffsetDateTime.now().toString());
        } finally {
            // Only delete objects created by this invocation. Existing candidate indexes are never changed.
            if(createdIndex)jdbc.execute("DROP INDEX "+INDEX+" ON orders");
            if(owner!=null){
                jdbc.update("DELETE i FROM order_items i JOIN orders o ON o.id=i.order_id JOIN import_batches b ON b.id=o.batch_id WHERE b.owner_id=?",owner);
                jdbc.update("DELETE o FROM orders o JOIN import_batches b ON b.id=o.batch_id WHERE b.owner_id=?",owner);
                jdbc.update("DELETE FROM export_log WHERE actor_id=?",owner);jdbc.update("DELETE FROM audit_log WHERE actor_id=?",owner);
                jdbc.update("DELETE FROM import_batches WHERE owner_id=?",owner);jdbc.update("DELETE FROM users WHERE id=?",owner);
            }
            for(long id:productIds)jdbc.update("DELETE FROM products WHERE id=?",id);
            for(long id:storeIds)jdbc.update("DELETE FROM stores WHERE id=?",id);
            result.put("finalTableCounts",counts());result.put("cleanupRestoredCounts",counts().equals(initialCounts));
            json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("result.json").toFile(),result);
        }
        assertThat(counts()).isEqualTo(initialCounts);
    }

    List<Path> generate() throws Exception {
        var files=new ArrayList<Path>();
        for(int batch=0;batch<20;batch++){
            Path p=output.resolve(String.format("orders-%02d.csv",batch));
            try(var csv=new CSVPrinter(Files.newBufferedWriter(p,StandardCharsets.UTF_8),CSVFormat.RFC4180)){
                csv.printRecord((Object[])CsvValidator.HEADER.split(","));
                for(int i=batch*2500;i<(batch+1)*2500;i++){
                    int k=i/10;String status=k%200==0?"REFUNDED":k%10==1?"CANCELLED":"PAID";
                    var time=LocalDate.of(2085,1,1).plusDays(k*137L%365).atStartOfDay().plusSeconds(k*7919L%86000);
                    int q1=1+i%3,q2=1+(i+1)%3,u1=100+i*17%10000,u2=100+(i*17+53)%10000,total=q1*u1+q2*u2;
                    for(int j=0;j<2;j++)csv.printRecord(PREFIX+"S"+(i%10),String.format("M3-%06d",i),status,
                        stamp(time),status.equals("CANCELLED")?"":stamp(time.plusSeconds(60)),status.equals("REFUNDED")?stamp(time.plusDays(2).plusSeconds(60)):"",
                        CsvExport.money(total),j+1,PREFIX+"P"+((i*7+j)%20),j==0?q1:q2,CsvExport.money(j==0?u1:u2),CsvExport.money(j==0?q1*u1:q2*u2));
                }
            }
            assertThat(Files.size(p)).isLessThanOrEqualTo(CsvValidator.MAX_BYTES);files.add(p);
        }
        return files;
    }
    static String stamp(LocalDateTime t){return t.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));}
    long id(String table,String code){return jdbc.queryForObject("SELECT id FROM "+table+" WHERE code=?",Long.class,code);}
    boolean indexExists(){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='orders' AND index_name=?",Long.class,INDEX)>0;}
    Map<String,Long> counts(){var m=new LinkedHashMap<String,Long>();for(String table:List.of("stores","products","users","import_batches","orders","order_items","audit_log","export_log"))m.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return m;}
    Query bound(String method,Map<String,Object> params){var sql=sessions.getConfiguration().getMappedStatement("com.orderreporting.OrderMapper."+method).getBoundSql(params);return new Query(sql.getSql().replaceAll("\\s+"," ").trim(),sql.getParameterMappings().stream().map(p->params.get(p.getProperty())).toList());}
    Query withoutIndex(Query q){return new Query(q.sql().replace("FROM orders o","FROM orders o IGNORE INDEX ("+INDEX+")"),q.parameters());}
    double execute(Query q){long start=System.nanoTime();jdbc.queryForList(q.sql(),q.parameters().toArray());return (System.nanoTime()-start)/1e6;}
    Map<String,Object> measure(Query q){
        var m=new LinkedHashMap<String,Object>();m.put("sql",q.sql());m.put("explain",jdbc.queryForList("EXPLAIN "+q.sql(),q.parameters().toArray()));
        m.put("explainAnalyze",jdbc.queryForObject("EXPLAIN ANALYZE "+q.sql(),String.class,q.parameters().toArray()));
        for(int i=0;i<5;i++)execute(q);var times=new ArrayList<Double>();for(int i=0;i<20;i++)times.add(execute(q));m.putAll(stats(times));return m;
    }
    Map<String,Object> stats(List<Double> values){var sorted=new ArrayList<>(values);Collections.sort(sorted);return Map.of("samplesMs",values,"medianMs",(sorted.get(9)+sorted.get(10))/2,"p95Ms",sorted.get(18),"minMs",sorted.getFirst(),"maxMs",sorted.getLast());}
}
