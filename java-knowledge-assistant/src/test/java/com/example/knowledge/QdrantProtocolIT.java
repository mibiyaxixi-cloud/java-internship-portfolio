package com.example.knowledge;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import com.example.knowledge.Model.*;
/** Local HTTP failure fixture, not another vector store and not a quality evaluation. */
@EnabledIfSystemProperty(named="run.integration",matches="true")
class QdrantProtocolIT {
    HttpServer server;QdrantIndex index;AppProperties props;volatile String query;volatile int responseCode=200;volatile long delay;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",ex->{
            var path=ex.getRequestURI().getPath();String body=new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(path.endsWith("/points/query"))query=body;
            if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            String data=path.endsWith("/points/query")?"{\"result\":{\"points\":[]}}":path.endsWith("/points/delete")?"{\"result\":{\"status\":\"completed\"}}":"{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":256}}}}}";
            try{byte[] bytes=data.getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(responseCode,bytes.length);ex.getResponseBody().write(bytes);}finally{ex.close();}
        });server.start();
        props=new AppProperties("mock","","","","","","",256,"http://127.0.0.1:"+server.getAddress().getPort(),"",2,600,80,4,.2,"","","");
        index=new QdrantIndex(props,new ObjectMapper(),new GuardProperties(10,20,30,4,2,1,35));
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    float[] vector(){var v=new float[256];v[0]=1;return v;}
    @Test void queryUsesDocumentAndVersionPairs() throws Exception {
        index.search(vector(),16,List.of(new ActiveVersion("doc1",2),new ActiveVersion("doc2",1)));
        var rules=new ObjectMapper().readTree(query).path("filter").path("should");assertEquals(2,rules.size());
        assertEquals("doc1",rules.get(0).path("must").get(0).path("match").path("value").asText());assertEquals(2,rules.get(0).path("must").get(1).path("match").path("value").asInt());
        assertEquals(1,rules.get(1).path("must").get(1).path("match").path("value").asInt());
    }
    @Test void missingCleanupCollectionIsAlreadyCleanButForeignPathIsRejected(){responseCode=404;assertDoesNotThrow(()->index.delete(props.collection(),List.of(UUID.randomUUID().toString())));assertEquals("CLEANUP_CONFIG_UNKNOWN",assertThrows(ApiException.class,()->index.delete("../../other",List.of(UUID.randomUUID().toString()))).code);}
    @Test void unavailableServiceFailsWithoutInventingEvidence(){responseCode=503;assertEquals("QDRANT_UNAVAILABLE",assertThrows(ApiException.class,()->index.search(vector(),4,List.of(new ActiveVersion("d",1)))).code);}
    @Test void slowVectorServiceIsBounded(){delay=2000;assertTimeout(java.time.Duration.ofSeconds(3),()->assertEquals("QDRANT_UNAVAILABLE",assertThrows(ApiException.class,()->index.search(vector(),4,List.of(new ActiveVersion("d",1)))).code));}
}
