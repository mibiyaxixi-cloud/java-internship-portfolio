CREATE TABLE idempotent_request (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) NOT NULL,
    resource_id BIGINT NOT NULL,
    response_status SMALLINT NULL,
    response_json MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, request_key),
    CONSTRAINT fk_request_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE RESTRICT,
    CONSTRAINT ck_request_response CHECK (
        (response_status IS NULL AND response_json IS NULL) OR
        (response_status BETWEEN 200 AND 299 AND response_json IS NOT NULL AND JSON_VALID(response_json))
    )
) ENGINE=InnoDB;
-- 请求登记、业务写入和成功响应在同一事务提交。未完成状态不会单独提交。
-- 不自动清理成功键；清理会改变很晚重试时的语义，需另行设计保留期。
