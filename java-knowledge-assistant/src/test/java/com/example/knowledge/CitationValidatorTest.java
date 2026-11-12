package com.example.knowledge;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.example.knowledge.Model.*;
class CitationValidatorTest {
    final CitationValidator validator=new CitationValidator();
    final Evidence evidence=new Evidence("chunk-a","doc-a",1,"a.md","报销","上限80元",true);
    Claim claim(String id,String quote){return new Claim("上限80元",List.of(id),Map.of(id,quote));}
    @Test void fabricatedIdRejectsEntireResponse(){assertEquals("INVALID_CITATION",assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(claim("chunk-a","80元"),claim("invented-id","不存在"))),List.of(evidence))).code);}
    @Test void documentIdCannotStandInForChunkId(){assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(claim("doc-a","80元"))),List.of(evidence)));}
    @Test void invalidAndUncitedClaimsAreRejected(){
        assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(new Claim("无引用",List.of(),Map.of()))),List.of(evidence)));
        assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(claim("chunk-a","内容"))),List.of(new Evidence("chunk-a","doc-a",1,"a.md","章","内容",false))));
    }
    @Test void noEvidenceNeverUsesModelFacts(){var c=validator.check(new Draft(false,List.of(claim("x","模型猜测"))),List.of());assertEquals("INSUFFICIENT",c.status());assertTrue(c.citationIds().isEmpty());}
    @Test void supportedIdsAreNumberedAndDeduplicated(){var c=validator.check(new Draft(false,List.of(new Claim("80元",List.of("chunk-a","chunk-a"),Map.of("chunk-a","80元")))),List.of(evidence));assertEquals("80元 [1]",c.answer());assertEquals(List.of("chunk-a"),c.citationIds());assertEquals("80元",c.quotes().get("chunk-a"));}
    @Test void refusalCannotCarryUnsupportedText(){assertThrows(ApiException.class,()->validator.check(new Draft(true,List.of(claim("chunk-a","80元"))),List.of(evidence)));}
    @Test void validIdWithFabricatedQuoteIsRejected(){assertEquals("INVALID_QUOTE",assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(claim("chunk-a","上限800元"))),List.of(evidence))).code);}
    @Test void missingExtraOrBlankQuoteIsRejected(){
        for(var quotes:List.of(Map.<String,String>of(),Map.of("chunk-a","80元","extra","80元"),Map.of("chunk-a"," ")))
            assertThrows(ApiException.class,()->validator.check(new Draft(false,List.of(new Claim("80元",List.of("chunk-a"),quotes))),List.of(evidence)));
    }
    @Test void realRefusalProducesOnlyFixedText(){var c=validator.check(new Draft(true,List.of()),List.of(evidence));assertEquals(CitationValidator.INSUFFICIENT,c.answer());assertTrue(c.quotes().isEmpty());}
}
