-- 显式演示数据：仅 demo profile 自动加载，也可手动重复执行以追加未来七天的时段。
-- 不更新已存在的设备或时段，不移动历史时间，不删除预约；所有演示记录标记 is_demo=TRUE。
INSERT INTO device (code, name, category, location, description, status, is_demo)
SELECT 'DEMO-MIC-01', '倒置荧光显微镜', '光学成像', '明理楼 · A302',
       '适用于细胞形态观察与荧光成像。使用前请完成实验室设备培训。', 'ACTIVE', TRUE
WHERE NOT EXISTS (SELECT 1 FROM device WHERE code = 'DEMO-MIC-01');
INSERT INTO device (code, name, category, location, description, status, is_demo)
SELECT 'DEMO-CEN-01', '高速冷冻离心机', '样品制备', '明理楼 · A305',
       '用于温度敏感样品的分离。请按转子规格配置平衡样品。', 'ACTIVE', TRUE
WHERE NOT EXISTS (SELECT 1 FROM device WHERE code = 'DEMO-CEN-01');
INSERT INTO device (code, name, category, location, description, status, is_demo)
SELECT 'DEMO-PCR-01', '实时荧光定量 PCR 仪', '分子检测', '明理楼 · B201',
       '用于核酸扩增和定量分析。当前处于维护状态，暂不开放。', 'INACTIVE', TRUE
WHERE NOT EXISTS (SELECT 1 FROM device WHERE code = 'DEMO-PCR-01');

INSERT INTO equipment_slot (device_id, start_at, end_at, status, is_demo)
SELECT d.id,
       TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL days.n DAY), hours.start_time),
       TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL days.n DAY), hours.end_time),
       'OPEN', TRUE
FROM device d
CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
            UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) days
CROSS JOIN (SELECT '09:00:00' start_time, '10:30:00' end_time
            UNION ALL SELECT '14:00:00', '15:30:00'
            UNION ALL SELECT '16:00:00', '17:30:00') hours
WHERE d.code IN ('DEMO-MIC-01', 'DEMO-CEN-01') AND d.is_demo = TRUE AND d.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM equipment_slot s WHERE s.device_id = d.id
      AND s.start_at = TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL days.n DAY), hours.start_time)
      AND s.end_at = TIMESTAMP(DATE_ADD(CURRENT_DATE(), INTERVAL days.n DAY), hours.end_time));
