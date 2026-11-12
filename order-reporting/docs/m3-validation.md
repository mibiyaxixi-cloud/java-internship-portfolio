# M3 实际验收记录

执行与记录：2026-10-13～14，Asia/Shanghai。范围仅 M3；没有扩展 M4、Redis、异步导入、Excel、定时汇总、Docker 或微服务。BookFlow 与 sources 保持只读参考。

## 完成结果

| 项目 | 实际结果与证据 |
|---|---|
| 完整回归 | 2026-10-14 22:36:59，4 项单元 + 31 项真实 MySQL 集成测试，0 失败/错误/跳过，打包成功：[测试明细](evidence/m3-tests.txt)、[构建摘要](evidence/m3-build-summary.txt) |
| 基准首轮 | 2026-10-13：20 个真实 CSV 批次，50,000 单/100,000 明细；无候选索引和创建后分别测量；结果与顺序一致 |
| 基准复现 | 2026-10-14：相同 20 个 CSV 哈希；V3 已有索引，用 IGNORE INDEX 做对照；两轮结束表行数均恢复：[复现检查](evidence/m3-reproducibility.json) |
| 索引迁移 | V3 创建 `(store_id,status,ordered_at,id)`；测试库与开发库均成功，原索引保留，V1/V2 未修改 |
| 后端启动 | 2026-10-14 22:39，8081 最终后端启动，开发库 V3 成功：[启动输出](evidence/m3-startup.txt) |
| HTTP 验收 | 重启后经 5174 代理，两份脚本均 PASS：[导入/订单/权限](evidence/m3-http-m1.json)、[报表/下钻/导出](evidence/m3-http-m2.json) |
| 前端构建 | `npm run build` 成功：[原始输出](evidence/m3-frontend-build.txt)；保留主 JS 超过 500 kB 警告，未测页面加载性能 |
| 演示/简历 | demo.md、resume-draft.md 已形成草稿；有 SQL/数据流和未代做练习 m3-learning.md |
| 浏览器交互 | **待补**。10/13～14 重试访问 5174 页面，管理策略校验仍不可用；没有绕过控制，也没有 M2/M3 图表点击、下载落盘或响应式截图证据 |

## 本阶段增加的回归

1. 相同文件重放、行序变化但订单语义相同的新文件：新增数为零，汇总金额和资金 CSV 字节保持一致。
2. 账号在预检查后被改绑到另一门店：确认按数据库最新身份拒绝，批次 FAILED 可读取且无新订单；报表和导出立即改为新门店范围，伪造 ADMIN authority 无效。测试结束恢复自己夹具账号的绑定。
3. 在 export_log 临时增加只拒绝本次新测试账号的 CHECK，模拟审计写入失败：API 返回 503 JSON，无 Content-Disposition，不把失败伪装成成功下载；最后删除本次约束/账号。

原 M1/M2 回归继续覆盖错一行整批拒绝、写入中失败全回滚和独立失败日志、并发同文件/不同文件争用、越权导入与导出、跨日/午夜/闰日、整单与行金额粒度、10,000 行导出与第 10,001 行拒绝、16 MiB 和公式文本防护。

这些是测试实际覆盖的边界；没有声称证明任意并发、崩溃恢复或全面安全。故障注入会产生预期数据库错误日志，以最后零失败统计为准。

## 数据保护与性能结论

只通过 TEST_DB_* 连接 `order_reporting_test`，应用上下文/Flyway 前校验库名。没有清库、TRUNCATE、Flyway clean 或用户业务数据删除。普通测试用随机夹具；基准固定前缀遇到既有对象就拒绝。所有清理按本次创建的 ID/身份限定，保留开发演示订单。

本机首轮退款分页实际扫描 4,173 → 20 行，JDBC 20 次暖缓存中位 3.306250 → 0.189146 ms；count 为 2.656438 → 0.090021 ms。仅此场景有证据，不能扩展为总体提速或生产规模。完整环境、SQL、全部样本和对照方法在 [performance.md](performance.md)。

## 复现

按 README 启动独立数据库，然后依次执行：

```bash
./scripts/backend.sh verify
./scripts/benchmark.sh
(cd frontend && npm run build)
# 后端/前端启动后运行；使用已有原演示数据，不清理开发库
API_BASE=http://127.0.0.1:5174/api node scripts/smoke.mjs
API_BASE=http://127.0.0.1:5174/api node scripts/smoke-m2.mjs
```

基准每次结果保存在新的 `.local/benchmark/时间/`。普通 verify 不启动十万行基准。没有主动再次下载依赖，也没有升级已固定版本。

剩余验收：浏览器工具恢复后，按 m2-validation.md 清单实际检查图表点击、事件筛选、下载、账号切换与窄窗口布局，再保存截图。该项保留为未完成，不用 API 验收替代视觉/交互结论。
