-- 不修改已经应用的 V1。演示账号只在显式 demo profile 下由 Java 安全哈希后初始化。
ALTER TABLE app_user ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user MODIFY password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 安全哈希，禁止存储明文';
