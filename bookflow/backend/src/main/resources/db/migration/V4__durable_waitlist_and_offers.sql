-- HELD 是限时确认资格，只有 CONFIRMED 才是有效预约；两者共同占用唯一容量。
ALTER TABLE reservation
    DROP CHECK ck_reservation_status,
    DROP CHECK ck_reservation_cancelled_at,
    ADD COLUMN offer_expires_at DATETIME(6) NULL,
    MODIFY COLUMN active_slot_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('CONFIRMED', 'HELD') THEN slot_id ELSE NULL END) STORED,
    ADD CONSTRAINT ck_reservation_status CHECK (status IN ('CONFIRMED', 'CANCELLED', 'HELD', 'EXPIRED')),
    ADD CONSTRAINT ck_reservation_cancelled_at CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR
        (status <> 'CANCELLED' AND cancelled_at IS NULL)
    ),
    ADD CONSTRAINT ck_reservation_offer CHECK (status NOT IN ('HELD', 'EXPIRED') OR offer_expires_at IS NOT NULL);

CREATE TABLE waitlist_entry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    queued_at DATETIME(6) NOT NULL,
    offered_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    reservation_id BIGINT NULL,
    reason VARCHAR(80) NULL,
    live_user_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('WAITING', 'OFFERED') THEN user_id ELSE NULL END) STORED,
    offered_slot_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'OFFERED' THEN slot_id ELSE NULL END) STORED,
    CONSTRAINT uk_waitlist_live_user UNIQUE (slot_id, live_user_id),
    CONSTRAINT uk_waitlist_offer UNIQUE (offered_slot_id),
    CONSTRAINT fk_waitlist_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_slot FOREIGN KEY (slot_id) REFERENCES equipment_slot(id) ON DELETE RESTRICT,
    CONSTRAINT fk_waitlist_reservation FOREIGN KEY (reservation_id) REFERENCES reservation(id) ON DELETE RESTRICT,
    CONSTRAINT ck_waitlist_status CHECK (status IN ('WAITING', 'OFFERED', 'CONFIRMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_waitlist_offer CHECK (status NOT IN ('OFFERED', 'CONFIRMED') OR
        (reservation_id IS NOT NULL AND offered_at IS NOT NULL AND expires_at > offered_at)),
    CONSTRAINT ck_waitlist_completed CHECK (
        (status IN ('WAITING', 'OFFERED') AND completed_at IS NULL) OR
        (status IN ('CONFIRMED', 'EXPIRED', 'CANCELLED') AND completed_at IS NOT NULL)
    ),
    INDEX idx_waitlist_queue (slot_id, status, id),
    INDEX idx_waitlist_user (user_id, id)
) ENGINE=InnoDB;

-- 每个时段一条可恢复调度状态。无内存消息或一次性定时任务作为事实来源。
CREATE TABLE waitlist_task (
    slot_id BIGINT NOT NULL PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    next_run_at DATETIME(6) NULL,
    failure_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(160) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_task_slot FOREIGN KEY (slot_id) REFERENCES equipment_slot(id) ON DELETE RESTRICT,
    CONSTRAINT ck_task_status CHECK (
        (status = 'PENDING' AND next_run_at IS NOT NULL) OR (status = 'DONE' AND next_run_at IS NULL)
    ),
    CONSTRAINT ck_task_failures CHECK (failure_count >= 0),
    INDEX idx_task_due (status, next_run_at)
) ENGINE=InnoDB;
