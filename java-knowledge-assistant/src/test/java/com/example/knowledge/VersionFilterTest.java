package com.example.knowledge;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import com.example.knowledge.Model.*;
class VersionFilterTest {
    Version version(String id,String status,String profile){return new Version(id,1,"h","内容",status,"","",profile,"","a.md",null,null,"NONE",null);}
    PlatformTransactionManager tm(){var m=mock(PlatformTransactionManager.class);when(m.getTransaction(any())).thenReturn(new SimpleTransactionStatus());return m;}
    @Test void staleDeletedUnpublishedForeignProfileAndUnknownVectorsNeverReachEvidence(){
        var db=mock(KnowledgeMapper.class);var ai=mock(AiGateway.class);var index=mock(VectorIndex.class);var p=TestProps.props();
        var service=new DocumentService(db,new Chunker(p),ai,index,p,tm());
        when(db.activeVersions(p.profile())).thenReturn(List.of(new ActiveVersion("good",1)));
        when(ai.embed(List.of("问题"))).thenReturn(List.of(new float[256]));
        when(index.search(any(),eq(16),anyList())).thenReturn(List.of(new Hit("old",.99),new Hit("deleted",.98),new Hit("pending",.97),new Hit("foreign",.96),new Hit("missing",.95),new Hit("good",.9)));
        for(String id:List.of("old","deleted","pending","foreign","good")){
            when(db.evidence(id)).thenReturn(new Evidence(id,id,1,id+".md","章","内容",id.equals("foreign")||id.equals("good")));
            when(db.version(id,1)).thenReturn(version(id,"INDEXED",id.equals("foreign")?"other":p.profile()));
        }
        assertEquals(List.of("good"),service.retrieve("问题").stream().map(Evidence::id).toList());
    }
    @Test void changedProfileRequiresExplicitReindexInsteadOfSilentReuse(){
        var db=mock(KnowledgeMapper.class);var p=TestProps.props();
        when(db.state("doc")).thenReturn(new State("doc","a.md",null,false,1,false));when(db.lock("doc")).thenReturn(new State("doc","a.md",null,false,1,false));
        when(db.version("doc",1)).thenReturn(version("doc","FAILED","old-profile"));
        var service=new DocumentService(db,new Chunker(p),mock(AiGateway.class),mock(VectorIndex.class),p,tm());
        assertEquals("PROFILE_CHANGED",assertThrows(ApiException.class,()->service.retry("doc")).code);verify(db,never()).claim(anyString(),anyInt(),anyString());
    }
}
