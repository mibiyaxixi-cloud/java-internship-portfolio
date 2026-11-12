package com.orderreporting;

import com.orderreporting.Model.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class CsvValidator {
    public static final int MAX_BYTES=1024*1024, MAX_ROWS=5000;
    public static final String HEADER="store_code,order_no,status,ordered_at,paid_at,refunded_at,order_total,line_no,product_code,quantity,unit_price,line_total";
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private final CatalogMapper catalog; private final OrderMapper orders; private final ObjectMapper json;
    public CsvValidator(CatalogMapper catalog,OrderMapper orders,ObjectMapper json){this.catalog=catalog;this.orders=orders;this.json=json;}
    public static String hash(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static class Group {
        long sourceLine,storeId,total; String external,status; LocalDateTime ordered,paid,refunded;
        List<Line> items=new ArrayList<>(); Set<Integer> lines=new HashSet<>();
        Group(long n,long store,String external,String status,LocalDateTime ordered,LocalDateTime paid,LocalDateTime refunded,long total){
            this.sourceLine=n;this.storeId=store;this.external=external;this.status=status;this.ordered=ordered;this.paid=paid;this.refunded=refunded;this.total=total;
        }
    }
    public Check validate(byte[] bytes, Actor actor){
        List<RowError> errors=new ArrayList<>(); Map<String,Group> groups=new LinkedHashMap<>();
        int rows=0; boolean forbidden=false;
        if(bytes.length==0 || bytes.length>MAX_BYTES)return new Check(0,List.of(),List.of(new RowError(1,"文件不能为空，且不得超过 1 MiB")),false);
        var stores=catalog.stores(null).stream().collect(Collectors.toMap(Catalog::code,Catalog::id));
        var products=catalog.products().stream().collect(Collectors.toMap(Catalog::code,Catalog::id));
        long nextLine=1;
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            try(CSVParser parser=CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).get().parse(new StringReader(text))){
                var it=parser.iterator();
                if(!it.hasNext() || !it.next().toList().equals(List.of(HEADER.split(","))))
                    return new Check(0,List.of(),List.of(new RowError(1,"表头必须与固定模板完全一致")),false);
                nextLine=parser.getCurrentLineNumber()+1;
                while(it.hasNext()){
                    long line=nextLine; CSVRecord r=it.next(); nextLine=parser.getCurrentLineNumber()+1; rows++;
                    if(rows>MAX_ROWS){add(errors,line,"最多 5000 条明细记录");break;}
                    try{
                        require(r.size()==12,"应有 12 列");
                        Long store=stores.get(r.get(0)); require(store!=null,"门店编码不存在");
                        if(!actor.admin() && !store.equals(actor.storeId())){
                            forbidden=true; throw new IllegalArgumentException("不能导入其他门店");
                        }
                        String external=r.get(1);require(!external.isBlank() && external.length()<=64 && external.equals(external.strip()) && external.codePoints().noneMatch(Character::isISOControl),"订单号须为 1—64 字符，不能含控制符或首尾空格");
                        String status=r.get(2);require(Set.of("PAID","CANCELLED","REFUNDED").contains(status),"状态必须为 PAID、CANCELLED 或 REFUNDED");
                        LocalDateTime ordered=date(r.get(3),false),paid=date(r.get(4),true),refunded=date(r.get(5),true);
                        require(status.equals("CANCELLED") ? paid==null && refunded==null : paid!=null && !paid.isBefore(ordered),"取消订单不可有支付/退款时间；已支付订单支付时间须不早于下单");
                        require(status.equals("REFUNDED") ? refunded!=null && !refunded.isBefore(paid) : refunded==null,"全额退款必须有不早于支付的退款时间；其他状态不可有退款时间");
                        long total=money(r.get(6),100000000000L), unit=money(r.get(10),100000000L), amount=money(r.get(11),100000000000L);
                        int no=integer(r.get(7),5000),qty=integer(r.get(9),10000);
                        Long product=products.get(r.get(8));require(product!=null,"商品编码不存在");
                        require(amount==Math.multiplyExact(unit,qty),"明细金额不等于单价 × 数量");
                        String key=store+"\u0000"+external;
                        Group g=groups.computeIfAbsent(key,k->new Group(line,store,external,status,ordered,paid,refunded,total));
                        require(g.status.equals(status) && g.ordered.equals(ordered) && Objects.equals(g.paid,paid) && Objects.equals(g.refunded,refunded) && g.total==total,"同一订单各行的状态、时间、总金额必须一致");
                        require(g.lines.add(no),"同一订单的明细行号重复");
                        g.items.add(new Line(no,product,qty,unit,amount));
                    }catch(IllegalArgumentException | java.time.DateTimeException | ArithmeticException e){add(errors,line,e.getMessage());}
                }
            }
        }catch(CharacterCodingException e){add(errors,1,"文件必须使用有效 UTF-8 编码");}
        catch(IOException | UncheckedIOException e){add(errors,nextLine,"CSV 引号或格式错误");}
        List<Draft> drafts=new ArrayList<>();
        for(Group g:groups.values()){
            if(g.items.stream().mapToLong(Line::lineCents).sum()!=g.total)add(errors,g.sourceLine,"订单总额与全部明细合计不一致");
            g.items.sort(Comparator.comparingInt(Line::lineNo));
            try{
                // Hash excludes physical row numbers and includes every persisted business field.
                var canonical=Arrays.asList(g.storeId,g.external,g.status,g.ordered,g.paid,g.refunded,g.total,g.items);
                String digest=hash(json.writeValueAsBytes(canonical));
                String existing=orders.hash(g.storeId,g.external);
                if(existing!=null && !existing.equals(digest))add(errors,g.sourceLine,"相同门店与订单号已存在不同内容，不允许覆盖");
                drafts.add(new Draft(g.sourceLine,g.storeId,g.external,g.status,g.ordered,g.paid,g.refunded,g.total,List.copyOf(g.items),digest));
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        }
        if(rows==0)add(errors,2,"至少需要一条明细");
        return new Check(rows,List.copyOf(drafts),List.copyOf(errors),forbidden);
    }
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private static void add(List<RowError> errors,long line,String message){if(errors.size()<100)errors.add(new RowError(line,message));}
    private static int integer(String s,int max){require(s.matches("[1-9][0-9]{0,7}"),"数量/行号必须是正整数");int n=Integer.parseInt(s);require(n<=max,"数量/行号超出上限 "+max);return n;}
    private static long money(String s,long max){
        require(s.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"),"金额必须是非负十进制，最多两位小数");
        long n=new BigDecimal(s).movePointRight(2).longValueExact();require(n>0 && n<=max,"金额必须大于零且不超过 "+(max/100)+" 元");return n;
    }
    private static LocalDateTime date(String s,boolean nullable){
        if(s.isEmpty() && nullable)return null;
        try{var d=LocalDateTime.parse(s,DATE);require(d.getYear()>=2000 && d.getYear()<=2100,"日期年份须为 2000—2100");return d;}
        catch(java.time.format.DateTimeParseException e){throw new IllegalArgumentException("日期须为有效 yyyy-MM-ddTHH:mm:ss（上海时间）");}
    }
}
