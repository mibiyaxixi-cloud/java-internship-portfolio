-- 实验室时间统一为 Asia/Shanghai。DATETIME 存储该时区的墙上时间，不做隐式 UTC 转换。
CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL COMMENT '仅允许安全哈希；M1 不创建登录用户',
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_user_username UNIQUE (username),
    CONSTRAINT ck_user_role CHECK (role IN ('USER', 'ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE device (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(60) NOT NULL,
    location VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_device_code UNIQUE (code),
    CONSTRAINT ck_device_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB;

CREATE TABLE equipment_slot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    start_at DATETIME(6) NOT NULL,
    end_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_slot_device FOREIGN KEY (device_id) REFERENCES device(id) ON DELETE RESTRICT,
    CONSTRAINT ck_slot_time CHECK (end_at > start_at),
    CONSTRAINT ck_slot_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT uk_device_slot_time UNIQUE (device_id, start_at, end_at)
) ENGINE=InnoDB;

CREATE TABLE reservation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    cancelled_at DATETIME(6) NULL,
    -- MySQL 唯一索引允许多个 NULL。取消记录保留，但不继续占用时段。
    -- 不使用 UNIQUE(slot_id, status)，否则同一时段只能保存一条取消历史。
    active_slot_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'CONFIRMED' THEN slot_id ELSE NULL END) STORED,
    CONSTRAINT uk_reservation_active_slot UNIQUE (active_slot_id),
    CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_slot FOREIGN KEY (slot_id) REFERENCES equipment_slot(id) ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_reservation_cancelled_at CHECK (
        (status = 'CONFIRMED' AND cancelled_at IS NULL) OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    INDEX idx_reservation_user_created (user_id, created_at),
    INDEX idx_reservation_slot (slot_id)
) ENGINE=InnoDB;
