package com.orderreporting;

import java.time.LocalDateTime;
import java.util.List;

public final class Model {
    private Model() {}
    public record Account(long id, String username, String passwordHash, String role, Long storeId) {}
    public record Actor(long id, String username, String role, Long storeId) {
        public boolean admin() { return role.equals("ADMIN"); }
    }
    public record Catalog(long id, String code, String name) {}
    public record RowError(long line, String reason) {}
    public record Line(int lineNo, long productId, int quantity, long unitCents, long lineCents) {}
    public record Draft(long sourceLine, long storeId, String externalId, String status,
                        LocalDateTime orderedAt, LocalDateTime paidAt, LocalDateTime refundedAt,
                        long totalCents, List<Line> items, String contentHash) {}
    public record Check(int rowCount, List<Draft> orders, List<RowError> errors, boolean forbidden) {}
    public static class Batch {
        public String id;
        public long ownerId;
        public String fileHash;
        public byte[] content;
        public String status;
        public int rowCount, orderCount, insertedCount, skippedCount;
        public String errorsJson;
        public LocalDateTime createdAt, updatedAt;
    }
    public record BatchView(String id, String status, int rowCount, int orderCount,
                            int insertedCount, int skippedCount, List<RowError> errors, LocalDateTime createdAt) {}
    public record Order(long id, long storeId, String storeName, String externalId, String status,
                        LocalDateTime orderedAt, LocalDateTime paidAt, LocalDateTime refundedAt,
                        long totalCents, String batchId) {}
    public record Item(int lineNo, String productCode, String productName, int quantity, long unitCents, long lineCents) {}
    public record Detail(Order order, List<Item> items) {}
    public record Page<T>(List<T> items, long total, int page, int size) {}
}
