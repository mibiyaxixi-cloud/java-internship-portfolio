CREATE TABLE stores (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL
);
CREATE TABLE products (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(32) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 name VARCHAR(100) NOT NULL
);
CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, role VARCHAR(16) NOT NULL, store_id BIGINT,
 CONSTRAINT fk_user_store FOREIGN KEY(store_id) REFERENCES stores(id),
 CONSTRAINT ck_user_scope CHECK ((role='ADMIN' AND store_id IS NULL) OR (role='STORE' AND store_id IS NOT NULL))
);
CREATE TABLE import_batches (
 id CHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, file_hash CHAR(64) NOT NULL,
 content MEDIUMBLOB NOT NULL, status VARCHAR(16) NOT NULL,
 row_count INT NOT NULL DEFAULT 0, order_count INT NOT NULL DEFAULT 0,
 inserted_count INT NOT NULL DEFAULT 0, skipped_count INT NOT NULL DEFAULT 0,
 errors_json MEDIUMTEXT NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_batch_owner FOREIGN KEY(owner_id) REFERENCES users(id),
 UNIQUE KEY uk_batch_file(owner_id, file_hash),
 CONSTRAINT ck_batch_status CHECK(status IN ('READY','INVALID','SUCCEEDED','FAILED'))
);
CREATE TABLE orders (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, store_id BIGINT NOT NULL,
 external_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL, status VARCHAR(16) NOT NULL,
 ordered_at DATETIME NOT NULL, paid_at DATETIME, refunded_at DATETIME,
 total_cents BIGINT NOT NULL, content_hash CHAR(64) NOT NULL, batch_id CHAR(36) NOT NULL,
 UNIQUE KEY uk_store_order(store_id, external_id),
 KEY ix_store_ordered_id(store_id, ordered_at, id),
 KEY ix_ordered_id(ordered_at, id),
 CONSTRAINT fk_order_store FOREIGN KEY(store_id) REFERENCES stores(id),
 CONSTRAINT fk_order_batch FOREIGN KEY(batch_id) REFERENCES import_batches(id),
 CONSTRAINT ck_total CHECK(total_cents BETWEEN 1 AND 100000000000),
 CONSTRAINT ck_order_events CHECK (
  (status='CANCELLED' AND paid_at IS NULL AND refunded_at IS NULL) OR
  (status='PAID' AND paid_at IS NOT NULL AND paid_at >= ordered_at AND refunded_at IS NULL) OR
  (status='REFUNDED' AND paid_at IS NOT NULL AND refunded_at IS NOT NULL AND paid_at >= ordered_at AND refunded_at >= paid_at)
 )
);
CREATE TABLE order_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, order_id BIGINT NOT NULL, line_no INT NOT NULL,
 product_id BIGINT NOT NULL, quantity INT NOT NULL, unit_cents BIGINT NOT NULL, line_cents BIGINT NOT NULL,
 UNIQUE KEY uk_order_line(order_id, line_no),
 CONSTRAINT fk_item_order FOREIGN KEY(order_id) REFERENCES orders(id),
 CONSTRAINT fk_item_product FOREIGN KEY(product_id) REFERENCES products(id),
 CONSTRAINT ck_item CHECK(line_no BETWEEN 1 AND 5000 AND quantity BETWEEN 1 AND 10000 AND unit_cents BETWEEN 1 AND 100000000 AND line_cents = quantity * unit_cents)
);
CREATE TABLE audit_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_id BIGINT NOT NULL, batch_id CHAR(36) NOT NULL,
 action VARCHAR(32) NOT NULL, detail VARCHAR(255) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_id) REFERENCES users(id), FOREIGN KEY(batch_id) REFERENCES import_batches(id)
);
