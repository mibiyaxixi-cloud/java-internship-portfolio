-- 只创建新的独立数据库，不连接 BookFlow。已有同名库时失败，先人工检查。
CREATE DATABASE order_reporting CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE order_reporting_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- 通过管理员客户端另建独立账号，各自仅授予对应库权限。参见 README。
