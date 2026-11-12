package com.orderreporting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ReportModel {
    private ReportModel() {}
    public record Filter(Long store, LocalDateTime start, LocalDateTime end, Long product, String eventType) {}
    public record Totals(long salesCents, long refundCents, long netCents, long paidOrders, long refundedOrders) {}
    public record Day(LocalDate day, long salesCents, long refundCents, long netCents, long paidOrders, long refundedOrders) {}
    public record Store(long storeId, String storeCode, String storeName, long salesCents, long refundCents,
                        long netCents, long paidOrders, long refundedOrders) {}
    public record Product(long productId, String productCode, String productName, long soldQuantity,
                          long refundedQuantity, long salesCents, long refundCents, long netCents) {}
    public record Overview(LocalDate from, LocalDate to, String timeZone, String dateBasis, Totals totals,
                           List<Day> daily, List<Store> stores, List<Product> products, int productLimit) {}
    public record EventLine(LocalDateTime eventAt, String eventType, long orderId, String externalId,
                            String status, long storeId, String storeCode, String storeName,
                            int lineNo, long productId, String productCode, String productName,
                            int quantity, long salesCents, long refundCents, long netCents) {}
}
