package com.orderreporting;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CsvExportTest {
    @Test void dangerousTextIsMarkedAsTextIncludingWhitespaceAndFullWidth() {
        for(String s:List.of("=1+2","+SUM(1,2)","-1+2","@SUM(1)","\t=1","\r=1","\n=1","  =1","\u00a0=1","\u200b=1","＝1","＋1","－1","＠1"))
            assertThat(CsvExport.text(s)).startsWith("'");
        assertThat(CsvExport.text("订单,\"A\"")).isEqualTo("订单,\"A\"");
        assertThat(CsvExport.money(-4000)).isEqualTo("-40.00");
    }
    @Test void fileByteCapFailsInsteadOfProducingPartialDownload() {
        assertThatThrownBy(()->{try(var csv=new CsvExport("text")){csv.row("x".repeat(CsvExport.MAX_BYTES+1));csv.finish();}})
            .isInstanceOfSatisfying(Problem.class,p->assertThat(p.code).isEqualTo("EXPORT_TOO_LARGE"));
    }
}
