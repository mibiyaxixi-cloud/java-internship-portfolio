-- One row per payment/refund event, never filtered by current PAID status.
-- This is a logical view, not a materialized/periodic summary.
CREATE SQL SECURITY INVOKER VIEW order_events AS
SELECT id AS order_id, store_id, 'PAYMENT' AS event_type, paid_at AS event_at,
       total_cents AS sales_cents, CAST(0 AS SIGNED) AS refund_cents
FROM orders WHERE paid_at IS NOT NULL
UNION ALL
SELECT id AS order_id, store_id, 'REFUND' AS event_type, refunded_at AS event_at,
       CAST(0 AS SIGNED) AS sales_cents, total_cents AS refund_cents
FROM orders WHERE refunded_at IS NOT NULL;

CREATE TABLE export_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 actor_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL,
 filters_json TEXT NOT NULL,
 row_count INT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES users(id),
 CHECK(kind IN ('ORDER_LEDGER','EVENT_LINES')),
 CHECK(row_count BETWEEN 0 AND 10000)
);
