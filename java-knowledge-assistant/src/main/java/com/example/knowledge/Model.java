package com.example.knowledge;

import java.time.LocalDateTime;
import java.util.*;

public final class Model {
    private Model() {}
    public record Account(String username, String passwordHash, String role) {}
    public record Doc(String id, String filename, Integer activeVersion, boolean available) {}
    public record State(String id, String filename, Integer activeVersion, boolean available, int latestVersion, boolean deleted) {}
    public record Version(String documentId, int version, String sourceHash, String content,
            String status, String stage, String failure, String profile, String attempt,
            String sourceFilename, String collectionName, String indexConfig, String cleanupStatus, String cleanupFailure) {}
    public record DocView(String id, String filename, Integer activeVersion, boolean available,
            int version, String status, String stage, String failure, String profile, boolean deleted,
            String sourceFilename, int cleanupPending, String cleanupFailure) {}
    public record VersionView(int version, String sourceFilename, String status, String stage, String failure,
            String profile, String cleanupStatus, String cleanupFailure, boolean active) {}
    public record ActiveVersion(String documentId, int version) {}
    public record Chunk(String id, String documentId, int version, int ordinal, String section, String content) {}
    public record Hit(String id, double score) {}
    public record Evidence(String id, String documentId, int version, String filename, String section, String content, boolean valid, String quote) {
        @org.apache.ibatis.annotations.AutomapConstructor public Evidence {}
        public Evidence(String id,String doc,int version,String file,String section,String content,boolean valid) {
            this(id,doc,version,file,section,content,valid,null);
        }
    }
    public record Claim(String text, List<String> citationIds, Map<String,String> quotes) {}
    public record Draft(boolean insufficient, List<Claim> claims) {}
    public record Checked(String answer, String status, List<String> citationIds, Map<String,String> quotes) {
        public Checked(String answer,String status,List<String> ids) { this(answer,status,ids,Map.of()); }
    }
    public record Chat(String id, String username, String question, String answer, String status, String mode,
            String model, String embedding, String profile, int topK, double minScore, long latencyMs, LocalDateTime createdAt) {}
    public record ChatView(Chat chat, List<Evidence> citations) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasMore) {
        public static <T> Page<T> from(List<T> rows,int page,int size) {
            return new Page<>(List.copyOf(rows.subList(0,Math.min(rows.size(),size))),page,size,rows.size()>size);
        }
        public static void validate(int page,int size) {
            if(page<0 || page>10000 || size<1 || size>50) throw new ApiException(400,"INVALID_PAGE","page须为0—10000，size须为1—50");
        }
    }
}
