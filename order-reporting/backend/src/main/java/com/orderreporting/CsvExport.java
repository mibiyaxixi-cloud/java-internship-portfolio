package com.orderreporting;

import org.apache.commons.csv.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Bounded CSV generated completely before committing an HTTP response. */
public final class CsvExport implements AutoCloseable {
    public static final int MAX_ROWS=10000, PAGE_SIZE=500, MAX_BYTES=16*1024*1024;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CSVPrinter csv;

    public CsvExport(String... header) {
        try {
            OutputStream limited=new FilterOutputStream(bytes) {
                @Override public void write(int b) throws IOException { limit(1); out.write(b); }
                @Override public void write(byte[] b,int off,int len) throws IOException { limit(len); out.write(b,off,len); }
                private void limit(int n) {
                    if(bytes.size()+n>MAX_BYTES)throw new Problem(422,"EXPORT_TOO_LARGE","CSV 超过 16 MiB，请缩小筛选范围");
                }
            };
            var writer=new OutputStreamWriter(limited,StandardCharsets.UTF_8);
            writer.write('\uFEFF');
            csv=new CSVPrinter(writer,CSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.ALL).get());
            csv.printRecord((Object[])header);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }

    public static void checkCount(long count) {
        if(count>MAX_ROWS)throw new Problem(422,"EXPORT_LIMIT","最多导出 10000 行，请缩小日期或门店范围");
    }
    public static String text(String value) {
        if(value==null)return "";
        // RFC quoting prevents delimiter breakout. Prefix potentially executable text too.
        int first=value.isEmpty()?-1:value.codePointAt(0);
        boolean controls=first>=0 && (first<=32 || Character.isWhitespace(first) || Character.isSpaceChar(first) || Character.getType(first)==Character.FORMAT);
        boolean formula=first>=0 && "=+-@＝＋－＠".indexOf(first)>=0;
        return controls || formula ? "'"+value : value;
    }
    public static String money(long cents) { return BigDecimal.valueOf(cents,2).toPlainString(); }
    public static String time(LocalDateTime value) { return value==null?"":value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
    public void row(Object... fields) {
        try { csv.printRecord(fields); } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public byte[] finish() {
        try { csv.flush(); return bytes.toByteArray(); } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public void close() {
        try { csv.close(); } catch(IOException e) { throw new UncheckedIOException(e); }
    }
}
