package com.example.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import com.example.knowledge.Model.*;

/** Local HTTP protocol fixture only. No actual provider, model, or embedding-quality evaluation. */
@EnabledIfSystemProperty(named="run.integration",matches="true")
class RealAdapterIT {
    HttpServer server;
    final Map<String,String> bodies=new ConcurrentHashMap<>(),keys=new ConcurrentHashMap<>();
    volatile String modelText="{\"insufficient\":false,\"claims\":[{\"text\":\"上限80元\",\"citationIds\":[\"chunk\"],\"quotes\":{\"chunk\":\"上限80元\"}}]}";
    final ObjectMapper json=new ObjectMapper();
    RealAiGateway gateway;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            var path=exchange.getRequestURI().getPath();bodies.put(path,new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));keys.put(path,exchange.getRequestHeaders().getFirst("Authorization"));
            Object response=path.contains("embeddings")?Map.of("object","list","model","embedding-fixture-v1","data",List.of(Map.of("object","embedding","index",0,"embedding",List.of(1.0,0.5))),"usage",Map.of("prompt_tokens",2,"total_tokens",2)):
                Map.of("id","fixture-response","object","chat.completion","created",0,"model","chat-fixture-v1","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",modelText),"finish_reason","stop")),"usage",Map.of("prompt_tokens",5,"completion_tokens",5,"total_tokens",10));
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        var p=new AppProperties("real",base+"/chat","fixture-chat-key","chat-fixture-v1",base+"/embed","fixture-embedding-key","embedding-fixture-v1",2,"","",2,600,80,4,.2,"","","");
        gateway=new RealAiGateway(p,json);
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    @Test void springAiUsesSeparateEndpointsKeysAndFixedModels() throws Exception {
        assertArrayEquals(new float[]{1f,.5f},gateway.embed(List.of("模拟原文")).getFirst());
        var answer=gateway.answer("报销上限？",List.of(new Evidence("chunk","doc",1,"模拟.md","规则","上限80元",true)));
        assertEquals("chunk",answer.claims().getFirst().citationIds().getFirst());
        assertEquals("上限80元",answer.claims().getFirst().quotes().get("chunk"));
        assertEquals("ANSWERED",new CitationValidator().check(answer,List.of(new Evidence("chunk","doc",1,"模拟.md","规则","上限80元",true))).status());
        assertEquals("Bearer fixture-chat-key",keys.get("/chat/v1/chat/completions"));
        assertEquals("Bearer fixture-embedding-key",keys.get("/embed/v1/embeddings"));
        var chatRequest=json.readTree(bodies.get("/chat/v1/chat/completions"));
        assertEquals("chat-fixture-v1",chatRequest.path("model").asText());
        assertEquals("system",chatRequest.path("messages").get(0).path("role").asText());
        assertEquals("user",chatRequest.path("messages").get(1).path("role").asText());
        assertEquals("embedding-fixture-v1",json.readTree(bodies.get("/embed/v1/embeddings")).path("model").asText());
    }
    @Test void malformedModelOutputFailsClosed() {
        modelText="猜测答案，不是 JSON";
        assertEquals("MODEL_FORMAT",assertThrows(ApiException.class,()->gateway.answer("问题",List.of())).code);
    }
}
