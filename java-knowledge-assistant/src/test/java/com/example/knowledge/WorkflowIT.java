package com.example.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.example.knowledge.Model.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="run.integration",matches="true")
@ContextConfiguration(initializers=WorkflowIT.TestDatabaseGuard.class)
@TestPropertySource(properties={"spring.datasource.url=${TEST_DB_URL}","spring.datasource.username=${TEST_DB_USERNAME}","spring.datasource.password=${TEST_DB_PASSWORD}","app.ai-mode=mock","app.embedding-base-url=integration-test","guard.questions-per-minute=1000","guard.imports-per-minute=1000"})
class WorkflowIT {
    public static class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext ctx) {
            String url=ctx.getEnvironment().getProperty("spring.datasource.url","");
            if(!url.matches("jdbc:mysql://[^/]+/knowledge_test(?:\\?.*)?")) throw new IllegalStateException("拒绝运行：集成测试只允许独立 knowledge_test 数据库");
        }
    }
    @Autowired MockMvc mvc;
    @Autowired KnowledgeMapper db;
    @Autowired DocumentService documents;
    @Autowired ChatService chats;
    @Autowired JdbcTemplate jdbc;
    @Autowired AppProperties props;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AiGateway ai;
    @MockitoSpyBean VectorIndex index;
    final List<String> docIds=new ArrayList<>(), chatIds=new ArrayList<>();
    final String content="# 模拟测试资料\n测试免责声明\n## 报销上限\n模拟网约车单次报销上限为80元。\n";
    String name() { return "IT-"+UUID.randomUUID()+".md"; }
    MockMultipartFile file(String name,String text) { return new MockMultipartFile("file",name,"text/markdown",text.getBytes(StandardCharsets.UTF_8)); }
    DocView upload() { var d=documents.upload(file(name(),content));docIds.add(d.id());assertEquals("INDEXED",d.status(),d.failure());return d; }
    ChatView ask(String user) { var c=chats.ask(user,"模拟网约车单次报销上限是多少？");chatIds.add(c.chat().id());return c; }

    @AfterEach void cleanup() throws Exception {
        reset(ai,index);
        for(String id:chatIds){jdbc.update("DELETE FROM chat_citations WHERE chat_id=?",id);jdbc.update("DELETE FROM chats WHERE id=?",id);}
        var points=new ArrayList<String>();
        for(String id:docIds)for(var v:db.versions(id))points.addAll(db.chunks(id,v.version()).stream().map(Chunk::id).toList());
        if(!points.isEmpty()) {
            var req=HttpRequest.newBuilder(URI.create(props.qdrantUrl()+"/collections/"+props.collection()+"/points/delete?wait=true"))
                .timeout(Duration.ofSeconds(10)).header("api-key",props.qdrantApiKey()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("points",points)))).build();
            assertEquals(200,HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        for(String id:docIds){jdbc.update("DELETE FROM chunks WHERE document_id=?",id);jdbc.update("DELETE FROM document_versions WHERE document_id=?",id);jdbc.update("DELETE FROM documents WHERE id=?",id);}
    }
    @Test void anonymousAndReaderCannotWriteAndCsrfIsRequired() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/documents").file(file(name(),content)).with(user("reader").roles("READER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/documents").file(file(name(),content)).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/documents/"+UUID.randomUUID()+"/retry").with(user("reader").roles("READER")).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void adminUploadAndReaderSourceFlow() throws Exception {
        var result=mvc.perform(multipart("/api/admin/documents").file(file(name(),content)).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk()).andReturn();
        var doc=json.readValue(result.getResponse().getContentAsString(),DocView.class);docIds.add(doc.id());assertEquals("INDEXED",doc.status());
        mvc.perform(get("/api/documents/"+doc.id()+"/versions/1").with(user("reader").roles("READER"))).andExpect(status().isOk()).andExpect(jsonPath("$.content").value(content));
        var c=ask("reader");assertEquals("ANSWERED",c.chat().status());assertFalse(c.citations().isEmpty());assertEquals("mock",c.chat().mode());
        assertTrue(c.chat().answer().contains("MOCK"));assertTrue(c.citations().stream().allMatch(Evidence::valid));
    }
    @Test void repeatedUploadAndRetryDoNotDuplicateChunks() {
        String name=name();var d=documents.upload(file(name,content));docIds.add(d.id());assertEquals("INDEXED",d.status());
        var original=db.chunks(d.id(),1);
        assertEquals(d.id(),documents.upload(file(name,content)).id());
        documents.retry(d.id());assertEquals(original,db.chunks(d.id(),1));
        assertEquals("REPLACE_REQUIRED",assertThrows(ApiException.class,()->documents.upload(file(name,content+"改变规则"))).code);
    }
    @Test void failedEmbeddingIsInvisibleAndRetryIsIdempotent() {
        doThrow(new ApiException(503,"EMBEDDING_UNAVAILABLE","测试故障：Embedding 不可用")).when(ai).embed(anyList());
        var d=documents.upload(file(name(),content));docIds.add(d.id());assertEquals("FAILED",d.status());assertFalse(d.available());assertEquals("EMBEDDING",d.stage());
        assertTrue(documents.list(false).stream().noneMatch(v->v.id().equals(d.id())));
        var before=db.chunks(d.id(),1);reset(ai);
        assertEquals("INDEXED",documents.retry(d.id()).status());assertEquals(before,db.chunks(d.id(),1));
    }
    @Test void partialVectorFailureNeverPublishesAndCanRetry() {
        doAnswer(inv->{inv.callRealMethod();throw new ApiException(503,"QDRANT_UNAVAILABLE","测试：向量已写入但响应丢失");}).when(index).upsert(anyList(),anyList());
        var d=documents.upload(file(name(),content));docIds.add(d.id());assertEquals("FAILED",d.status());assertFalse(d.available());
        assertTrue(documents.retrieve("模拟网约车上限").stream().noneMatch(e->e.documentId().equals(d.id())));
        var ids=db.chunks(d.id(),1).stream().map(Chunk::id).toList();reset(index);
        assertEquals("INDEXED",documents.retry(d.id()).status());assertEquals(ids,db.chunks(d.id(),1).stream().map(Chunk::id).toList());
    }
    @Test void otherUsersAndAdminCannotReadPrivateHistory() throws Exception {
        upload();var c=ask("reader");
        for(String user:List.of("reader2","admin")) {
            mvc.perform(get("/api/chats/"+c.chat().id()).with(user(user).roles(user.equals("admin")?"ADMIN":"READER"))).andExpect(status().isNotFound());
            assertTrue(chats.history(user).stream().noneMatch(v->v.chat().id().equals(c.chat().id())));
        }
        assertEquals(c.chat().id(),chats.get(c.chat().id(),"reader").chat().id());
    }
    @Test void fabricatedModelCitationIsNeverReturned() {
        upload();doReturn(new Draft(false,List.of(new Claim("伪造事实",List.of("invented-id"),Map.of())))).when(ai).answer(anyString(),anyList());
        var c=ask("reader");assertEquals("FAILED",c.chat().status());assertTrue(c.citations().isEmpty());
        assertFalse(c.chat().answer().contains("伪造事实"));assertTrue(c.chat().answer().contains("INVALID_CITATION"));
    }
    @Test void invalidVersionAndDeletedDocAreExcludedAndHistoryMarked() throws Exception {
        var d=upload();var c=ask("reader");assertFalse(c.citations().isEmpty());
        jdbc.update("UPDATE documents SET active_version=2 WHERE id=?",d.id());
        assertEquals("INDEX_PROFILE_CHANGED",assertThrows(ApiException.class,()->documents.retrieve("模拟网约车上限")).code);
        assertTrue(chats.get(c.chat().id(),"reader").citations().stream().noneMatch(Evidence::valid));
        mvc.perform(get("/api/chunks/"+c.citations().getFirst().id()).with(user("reader"))).andExpect(status().isGone());
        jdbc.update("UPDATE documents SET active_version=1,available=FALSE WHERE id=?",d.id());
        assertTrue(documents.retrieve("模拟网约车上限").stream().noneMatch(e->e.documentId().equals(d.id())));
    }
    @Test void noCandidatesSkipsGenerationAndQdrantFailureIsRecorded() {
        upload();
        doReturn(List.of()).when(index).search(any(),anyInt(),anyList());
        var c=ask("reader");assertEquals("INSUFFICIENT",c.chat().status());verify(ai,never()).answer(anyString(),anyList());
        doThrow(new ApiException(503,"QDRANT_UNAVAILABLE","向量服务不可用")).when(index).search(any(),anyInt(),anyList());
        var failed=ask("reader");assertEquals("FAILED",failed.chat().status());assertTrue(failed.citations().isEmpty());
    }
    @Test void invalidInputDoesNotCreateChat() throws Exception {
        mvc.perform(post("/api/chats").with(user("reader")).with(csrf()).contentType("application/json").content("{\"question\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/chats").with(user("reader")).with(csrf()).contentType("application/json").content("{\"question\":\"hi\",\"username\":\"admin\"}")).andExpect(status().isBadRequest());
    }
    @Test void replacementPublishesOnlyOnSuccessAndHistoryExpires() {
        var d=upload();var old=ask("reader");var oldChunks=db.chunks(d.id(),1);
        var next=documents.replace(d.id(),1,file("new-name.md",content.replace("80元","120元")));
        assertEquals(2,next.activeVersion());assertEquals("INDEXED",next.status());assertEquals("DONE",db.version(d.id(),1).cleanupStatus());
        assertEquals(oldChunks,db.chunks(d.id(),1));assertEquals("new-name.md",documents.source(d.id(),2).get("filename"));
        assertTrue(documents.retrieve("模拟网约车上限").stream().allMatch(e->e.version()==2));
        var answer=ask("reader");assertTrue(answer.chat().answer().contains("120元"));assertTrue(answer.citations().stream().allMatch(e->e.version()==2&&e.content().contains(e.quote())));
        assertTrue(chats.get(old.chat().id(),"reader").citations().stream().noneMatch(Evidence::valid));
        assertEquals(410,assertThrows(ApiException.class,()->documents.source(d.id(),1)).status);
        assertEquals("VERSION_SUPERSEDED",assertThrows(ApiException.class,()->documents.retry(d.id(),1)).code);
        assertEquals("VERSION_CONFLICT",assertThrows(ApiException.class,()->documents.replace(d.id(),1,file(name(),content+"新内容"))).code);
        assertEquals(2,documents.replace(d.id(),1,file("new-name.md",content.replace("80元","120元"))).version());
    }
    @Test void failedReplacementPreservesOldVersionAndRetryReusesChunks() {
        var d=upload();var old=ask("reader");
        doThrow(new ApiException(503,"EMBEDDING_UNAVAILABLE","测试故障")).when(ai).embed(anyList());
        var next=documents.replace(d.id(),1,file(name(),content.replace("80元","120元")));
        assertEquals("FAILED",next.status());assertEquals(1,next.activeVersion());assertTrue(next.available());
        assertTrue(chats.get(old.chat().id(),"reader").citations().stream().allMatch(Evidence::valid));
        assertTrue(documents.source(d.id(),1).get("content").toString().contains("80元"));
        var chunks=db.chunks(d.id(),2);reset(ai);assertEquals(2,documents.retry(d.id(),2).activeVersion());assertEquals(chunks,db.chunks(d.id(),2));
    }
    @Test void partialReplacementAndFailedCleanupCannotLeakStaleVectors() {
        var d=upload();
        doAnswer(inv->{inv.callRealMethod();throw new ApiException(503,"QDRANT_UNAVAILABLE","测试响应丢失");}).when(index).upsert(anyList(),anyList());
        var v2=documents.replace(d.id(),1,file(name(),content.replace("80元","120元")));assertEquals(1,v2.activeVersion());
        var old=db.chunks(d.id(),1).getLast();var pending=db.chunks(d.id(),2).getLast();
        doReturn(List.of(new Hit(pending.id(),.99),new Hit(old.id(),.98))).when(index).search(any(),anyInt(),anyList());
        assertEquals(List.of(old.id()),documents.retrieve("模拟网约车上限").stream().map(Evidence::id).toList());
        reset(index);doThrow(new ApiException(503,"QDRANT_UNAVAILABLE","测试删除不可用")).when(index).delete(anyString(),anyList());
        assertEquals(2,documents.retry(d.id()).activeVersion());assertEquals("FAILED",db.version(d.id(),1).cleanupStatus());
        doReturn(List.of(new Hit(old.id(),.99),new Hit(pending.id(),.98))).when(index).search(any(),anyInt(),anyList());
        assertEquals(List.of(pending.id()),documents.retrieve("模拟网约车上限").stream().map(Evidence::id).toList());
        reset(index);assertEquals(0,documents.cleanup(d.id()).cleanupPending());assertEquals("DONE",db.version(d.id(),1).cleanupStatus());
    }
    @Test void tombstoneCommitsBeforeFailedVectorCleanupAndCannotResurrect() {
        var d=upload();var old=ask("reader");
        doAnswer(inv->{assertTrue(db.state(d.id()).deleted());assertFalse(db.state(d.id()).available());throw new ApiException(503,"QDRANT_UNAVAILABLE","测试清理失败");}).when(index).delete(anyString(),anyList());
        var deleted=documents.delete(d.id(),1);assertTrue(deleted.deleted());assertEquals(1,deleted.cleanupPending());
        assertTrue(documents.retrieve("模拟网约车上限").isEmpty());assertTrue(chats.get(old.chat().id(),"reader").citations().stream().noneMatch(Evidence::valid));
        assertEquals("DOCUMENT_DELETED",assertThrows(ApiException.class,()->documents.retry(d.id())).code);
        assertEquals(410,assertThrows(ApiException.class,()->documents.source(d.id(),1)).status);
        reset(index);assertEquals(0,documents.cleanup(d.id()).cleanupPending());assertTrue(documents.delete(d.id(),1).deleted());
    }
    @Test void concurrentReplacementIsFencedAndDeletedImportCannotPublish() throws Exception {
        var d=upload();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return inv.callRealMethod();}).when(index).upsert(anyList(),anyList());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=pool.submit(()->documents.replace(d.id(),1,file(name(),content.replace("80元","120元"))));
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(1,db.state(d.id()).activeVersion());
                assertEquals("IMPORT_BUSY",assertThrows(ApiException.class,()->documents.replace(d.id(),1,file(name(),content.replace("80元","150元")))).code);
                var deleted=documents.delete(d.id(),1);assertTrue(deleted.deleted());assertEquals(1,deleted.cleanupPending());
            }finally{release.countDown();}
            var result=pending.get(5,TimeUnit.SECONDS);assertTrue(result.deleted());assertFalse(result.available());
            assertEquals("CANCELLED",db.version(d.id(),2).status());assertEquals("DONE",db.version(d.id(),2).cleanupStatus());assertTrue(documents.retrieve("模拟网约车").isEmpty());
        }
    }
    @Test void evidenceChangedDuringGenerationDiscardsWholeAnswer() throws Exception {
        var d=upload();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(inv->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return inv.callRealMethod();}).when(ai).answer(anyString(),anyList());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var pending=pool.submit(()->chats.ask("reader","模拟网约车单次报销上限是多少？"));
            try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(2,documents.replace(d.id(),1,file(name(),content.replace("80元","120元"))).activeVersion());}
            finally{release.countDown();}
            var result=pending.get(5,TimeUnit.SECONDS);chatIds.add(result.chat().id());
            assertEquals("FAILED",result.chat().status());assertTrue(result.chat().answer().contains("EVIDENCE_CHANGED"));assertTrue(result.citations().isEmpty());
        }
    }
    @Test void uncitedContextChangesAreAlsoRejected() {
        var first=upload();var second=upload();var c1=db.chunks(first.id(),1).getLast();var c2=db.chunks(second.id(),1).getLast();
        doReturn(List.of(new Hit(c1.id(),.99),new Hit(c2.id(),.98))).when(index).search(any(),anyInt(),anyList());
        doAnswer(inv->{documents.delete(second.id(),1);return new Draft(false,List.of(new Claim("80元",List.of(c1.id()),Map.of(c1.id(),"80元"))));}).when(ai).answer(anyString(),anyList());
        var result=ask("reader");assertEquals("FAILED",result.chat().status());assertTrue(result.chat().answer().contains("EVIDENCE_CHANGED"));
    }
    @Test void failedVersionCanBeSupersededWithoutReusingItsNumber() {
        var d=upload();doThrow(new ApiException(503,"QDRANT_UNAVAILABLE","测试故障")).when(index).upsert(anyList(),anyList());
        assertEquals("FAILED",documents.replace(d.id(),1,file(name(),content+"第二版")).status());reset(index);
        assertEquals(3,documents.replace(d.id(),1,file(name(),content+"第三版")).activeVersion());
        assertEquals("SUPERSEDED",db.version(d.id(),2).status());assertEquals("DONE",db.version(d.id(),2).cleanupStatus());
    }
    @Test void quoteMustBeInThisEvidenceAndRefusalCannotExposeModelGuess() {
        upload();doAnswer(inv->{List<Evidence> evidence=inv.getArgument(1);String id=evidence.getFirst().id();return new Draft(false,List.of(new Claim("伪造",List.of(id),Map.of(id,"资料没有的原文"))));}).when(ai).answer(anyString(),anyList());
        var failed=ask("reader");assertEquals("FAILED",failed.chat().status());assertTrue(failed.chat().answer().contains("INVALID_QUOTE"));assertTrue(failed.citations().isEmpty());
        doReturn(new Draft(true,List.of())).when(ai).answer(anyString(),anyList());var refused=ask("reader");assertEquals("INSUFFICIENT",refused.chat().status());assertEquals(CitationValidator.INSUFFICIENT,refused.chat().answer());
    }
    @Test void newAdminRoutesDenyReadersAndVersionInputsAreValidated() throws Exception {
        var d=upload();
        mvc.perform(multipart("/api/admin/documents/"+d.id()+"/versions").file(file(name(),content)).param("expectedVersion","1").with(user("reader")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/documents/"+d.id()).param("expectedVersion","1").with(user("reader")).with(csrf())).andExpect(status().isForbidden());
        for(String suffix:List.of("/cleanup","/versions/1/retry"))mvc.perform(post("/api/admin/documents/"+d.id()+suffix).with(user("reader")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/documents/"+d.id()+"/versions").with(user("reader"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/documents/"+d.id()).param("expectedVersion","1").with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/documents/"+d.id()).param("expectedVersion","-1").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/documents?page=-1").with(user("reader"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/chats?size=100").with(user("reader"))).andExpect(status().isBadRequest());
        assertFalse(db.state(d.id()).deleted());
    }
    @Test void historyPaginationRemainsScopedAndDocumentsUseActiveVersionForReaders() {
        var d=upload();ask("reader");ask("reader");var other=ask("reader2");
        var page=chats.page("reader",0,1);assertTrue(page.hasMore());assertEquals(1,page.items().size());assertEquals("reader",page.items().getFirst().chat().username());
        assertNotEquals(page.items().getFirst().chat().id(),chats.page("reader",1,1).items().getFirst().chat().id());
        assertTrue(chats.page("admin",0,50).items().stream().noneMatch(c->c.chat().id().equals(other.chat().id())));
        doThrow(new ApiException(503,"EMBEDDING_UNAVAILABLE","测试故障")).when(ai).embed(anyList());documents.replace(d.id(),1,file(name(),content+"第二版"));
        assertEquals(1,documents.page(false,0,12).items().getFirst().version());assertEquals(2,documents.page(true,0,12).items().getFirst().version());
    }
    @Test void cleanupAttemptTokenRejectsLateAcknowledgement() {
        var d=upload();db.queueCleanup(d.id(),1);assertEquals(1,db.claimCleanup(d.id(),1,"old-attempt"));
        db.queueCleanup(d.id(),1);assertEquals(0,db.finishCleanup(d.id(),1,"old-attempt","DONE",null));assertEquals("PENDING",db.version(d.id(),1).cleanupStatus());
        assertEquals(1,documents.cleanup(d.id()).cleanupPending());verify(index,never()).delete(anyString(),anyList());
    }
}
