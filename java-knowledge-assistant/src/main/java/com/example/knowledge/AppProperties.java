package com.example.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@ConfigurationProperties("app")
public record AppProperties(String aiMode, String chatBaseUrl, String chatApiKey, String chatModel,
        String embeddingBaseUrl, String embeddingApiKey, String embeddingModel, int embeddingDimensions,
        String qdrantUrl, String qdrantApiKey, int timeoutSeconds, int chunkSize, int chunkOverlap,
        int topK, double minScore, String adminPassword, String readerPassword, String reader2Password) {
    public AppProperties {
        if (!"mock".equals(aiMode) && !"real".equals(aiMode)) throw new IllegalArgumentException("AI_MODE 必须为 mock 或 real");
        if (chunkSize < 100 || chunkSize > 2000 || chunkOverlap < 0 || chunkOverlap >= chunkSize / 2)
            throw new IllegalArgumentException("切块参数无效");
        if (topK < 1 || topK > 10 || !Double.isFinite(minScore) || minScore < 0 || minScore > 1 || timeoutSeconds < 1 || timeoutSeconds > 120)
            throw new IllegalArgumentException("检索/超时参数无效");
        if (embeddingDimensions < 1 || embeddingDimensions > 65536) throw new IllegalArgumentException("向量维度无效");
        if ("real".equals(aiMode)) {
            require(chatBaseUrl, "CHAT_BASE_URL"); require(chatApiKey, "CHAT_API_KEY"); require(chatModel, "CHAT_MODEL");
            require(embeddingBaseUrl, "EMBEDDING_BASE_URL"); require(embeddingApiKey, "EMBEDDING_API_KEY"); require(embeddingModel, "EMBEDDING_MODEL");
        }
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("真实模式缺少 " + name + "，不会自动回退 mock");
    }
    public boolean mock() { return "mock".equals(aiMode); }
    public int dimensions() { return mock() ? 256 : embeddingDimensions; }
    public String chatLabel() { return mock() ? "mock-extract-v2" : chatModel; }
    public String embeddingLabel() { return mock() ? "mock-bigram-hash-v1" : embeddingModel; }
    public String profile() { return sha256(aiMode + "|" + embeddingBaseUrl + "|" + embeddingLabel() + "|" + dimensions() + "|section-v1|" + chunkSize + "|" + chunkOverlap); }
    public String collection() { return "knowledge_" + aiMode + "_" + profile().substring(0, 16); }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
