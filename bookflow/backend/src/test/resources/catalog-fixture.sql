INSERT INTO device (id, code, name, category, location, description, status, is_demo)
VALUES (900001, 'IT-MIC', '集成测试显微镜', '光学成像', '测试实验室', '仅在测试事务中存在', 'ACTIVE', FALSE),
       (900002, 'IT-EMPTY', '无时段设备', '样品制备', '测试实验室', '', 'ACTIVE', FALSE);
INSERT INTO equipment_slot (id, device_id, start_at, end_at, status)
VALUES (900001, 900001, '2035-01-01 09:00:00', '2035-01-01 10:30:00', 'OPEN'),
       (900002, 900001, '2035-01-01 14:00:00', '2035-01-01 15:30:00', 'OPEN'),
       (900003, 900001, '2035-01-02 09:00:00', '2035-01-02 10:30:00', 'OPEN');
-- 不可登录的测试夹具，无生产/演示账号，也不存储明文密码。
INSERT INTO app_user (id, username, password_hash, display_name, role, enabled)
VALUES (900001, 'it-user-a', '{disabled-test-fixture}', '测试 A', 'USER', FALSE),
       (900002, 'it-user-b', '{disabled-test-fixture}', '测试 B', 'USER', FALSE);
INSERT INTO reservation (user_id, slot_id, status) VALUES (900001, 900002, 'CONFIRMED');
