-- Measured on 50,000 orders / 100,000 items; evidence: docs/performance.md.
-- Equality predicates first, then the date range and deterministic paging order.
-- Keep ix_store_ordered_id for queries without a status filter.
CREATE INDEX ix_store_status_ordered_id ON orders(store_id,status,ordered_at,id);
