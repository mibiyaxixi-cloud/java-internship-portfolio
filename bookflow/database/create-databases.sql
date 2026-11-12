-- 使用已有 MySQL 时，由管理员执行一次；已有数据库保留。
-- 然后自行创建专用用户并授权（见 README），把账号密码写入 .env。
CREATE DATABASE IF NOT EXISTS bookflow CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS bookflow_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- 业务表由 Flyway 自动创建，不在这里手动建表。
