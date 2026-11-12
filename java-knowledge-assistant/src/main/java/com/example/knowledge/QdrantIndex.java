package com.example.knowledge;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.example.knowledge.Model.*;

/** Small explicit REST adapter: stable point IDs, wait=true publication barrier, bounded network time. */
@Component
public class QdrantIndex implements VectorIndex {
    private final AppProperties props;
    private final ObjectMapper json;
    private final int timeoutSeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public QdrantIndex(AppProperties props, ObjectMapper json, GuardProperties guard) { this.props=props; this.json=json; this.timeoutSeconds=guard.qdrantTimeoutSeconds(); }
    private String path() { return "/collections/"+props.collection(); }
    private JsonNode call(String method, String path, Object body, boolean allowMissing) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(props.qdrantUrl()+path))
                .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type","application/json");
            if (props.qdrantApiKey()!=null && !props.qdrantApiKey().isBlank()) builder.header("api-key",props.qdrantApiKey());
            builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (allowMissing && response.statusCode()==404) return null;
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new ApiException(503,"QDRANT_UNAVAILABLE","向量服务返回 HTTP "+response.statusCode()+"；检查服务地址、密钥及日志后重试");
            return json.readTree(response.body()).path("result");
        } catch (ApiException e) { throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (Exception e) { throw unavailable(); }
    }
    private ApiException unavailable() { return new ApiException(503,"QDRANT_UNAVAILABLE","向量服务不可用或超时；检查 Qdrant 地址、密钥和服务日志，再手动重试"); }
    private synchronized void ensureCollection() {
        var data = call("GET",path(),null,true);
        if (data == null) {
            call("PUT",path(),Map.of("vectors",Map.of("size",props.dimensions(),"distance","Cosine")),false);
        } else if (data.path("config").path("params").path("vectors").path("size").asInt()!=props.dimensions()) {
            throw new ApiException(503,"VECTOR_DIMENSIONS","Qdrant 集合维度与 Embedding 不匹配，不能混用旧索引");
        }
    }
    void validateVector(float[] vector) {
        if (vector == null || vector.length != props.dimensions()) throw new ApiException(502,"VECTOR_DIMENSIONS","Embedding 返回维度不匹配");
        double norm=0;
        for(float v:vector) { if(!Float.isFinite(v)) throw new ApiException(502,"VECTOR_INVALID","Embedding 返回非有限数值"); norm+=v*v; }
        if (norm==0) throw new ApiException(502,"VECTOR_INVALID","Embedding 返回零向量");
    }
    public void upsert(List<Chunk> chunks, List<float[]> vectors) {
        if (vectors.size()!=chunks.size()) throw new ApiException(502,"VECTOR_COUNT","Embedding 数量与片段数量不一致");
        vectors.forEach(this::validateVector);
        ensureCollection();
        var points = new ArrayList<Map<String,Object>>();
        for (int i=0;i<chunks.size();i++) {
            var c=chunks.get(i);
            points.add(Map.of("id",c.id(),"vector",vectors.get(i),"payload",Map.of("document_id",c.documentId(),"version",c.version(),"section",c.section(),"chunk_id",c.id(),"profile",props.profile())));
        }
        var result=call("PUT",path()+"/points?wait=true",Map.of("points",points),false);
        if (!"completed".equals(result.path("status").asText())) throw unavailable();
    }
    public List<Hit> search(float[] vector, int limit, List<ActiveVersion> active) {
        if(active.isEmpty())return List.of();
        validateVector(vector); ensureCollection();
        var allowed=active.stream().map(v->Map.of("must",List.of(
            Map.of("key","document_id","match",Map.of("value",v.documentId())),
            Map.of("key","version","match",Map.of("value",v.version()))))).toList();
        var data=call("POST",path()+"/points/query",Map.of("query",vector,"limit",limit,"with_payload",false,
            "score_threshold",props.minScore(),"filter",Map.of("should",allowed)),false);
        if(!data.path("points").isArray())throw unavailable();
        var hits=new ArrayList<Hit>();
        for(var point:data.path("points"))hits.add(new Hit(point.path("id").asText(),point.path("score").asDouble()));
        return hits;
    }
    public void delete(String collection,List<String> pointIds) {
        if(collection==null || !collection.matches("knowledge_(mock|real)_[a-f0-9]{16}"))
            throw new ApiException(409,"CLEANUP_CONFIG_UNKNOWN","集合信息无效，不能执行向量清理");
        if(pointIds.isEmpty())return;
        pointIds.forEach(UUID::fromString);
        var result=call("POST","/collections/"+collection+"/points/delete?wait=true",Map.of("points",pointIds),true);
        if(result!=null && !"completed".equals(result.path("status").asText()))throw unavailable();
    }
}
