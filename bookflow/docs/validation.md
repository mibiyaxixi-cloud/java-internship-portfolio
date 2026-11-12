# 最终验收记录（M1～M4）

日期：2026-10-10，Asia/Shanghai。环境为 macOS arm64、Java 21.0.12.1、MySQL 8.4.11、Node 24.21.0；固定依赖见 versions.md。所有测试结果为实际执行，没有使用 H2 替代 MySQL。

## 自动构建与数据库验证

| 命令 / 套件 | 实际结果 |
| --- | --- |
| `./scripts/backend.sh verify` | BUILD SUCCESS，可执行 JAR 打包成功 |
| CatalogServiceTest | 4 项单元测试，0 失败/错误/跳过 |
| CatalogMySqlIT | 9 项真实 MySQL 测试，0 失败/错误/跳过 |
| BookingWorkflowMySqlIT | 16 项真实登录/CSRF/预约/管理测试，0 失败/错误/跳过 |
| ReliabilityMySqlIT | 19 项事务、幂等、并发、候补测试，0 失败/错误/跳过 |
| `npm --prefix frontend run build` | Vite 生产构建成功 |
| Bash / Node 脚本语法检查 | 通过 |
| 演示配置模板/生成脚本 | 生成 3 个随机密码；文件权限 600；重复执行不覆盖 |

Java 合计 **48 项**（4 单元 + 44 MySQL 集成）。最终后端日志在 `.local/final-verify.log`，机器报告在 backend/target 的 surefire-reports / failsafe-reports。它们是本机输出，未作为源码依赖。

M3/M4 核心验证：10 位不同用户同抢一时段，1 成功、9 冲突，最终 1 条有效记录；8 个同用户同键请求并发，只创建 1 条记录、1 个原响应，其余重放；取消后旧键不产生新记录、新键可重约；取消与新预约、取消与候补优先权竞争；同用户并发入队只保留一条活动候补。

验证了排队顺序、HELD 阻止新预约、确认前后同一预约 ID、确认与两个超时工作线程在期限边界竞争、重复确认与重复任务、主动放弃后的递补、期限不超过时段开始、开始后队列结束、停用账号跳过。直接 SQL 在 HELD 期间插入 CONFIRMED 被唯一约束拒绝。

失败注入覆盖：业务成功后响应保存前抛异常时请求登记和预约都回滚；取消/递补、超时/递补在提交前异常时全部回滚；失败任务登记延迟重试后仍能递补。测试使用独立线程与连接，夹具实际提交，结束只清理各自的 `_test` 数据。

## 运行中 HTTP 和实际进程恢复

| 脚本 | 实际结果 |
| --- | --- |
| `node scripts/smoke.mjs` | 6 项目录/参数/权限检查通过 |
| `node scripts/smoke-m2.mjs` | 10 组登录、权限、预约取消和管理操作通过；M4 升级后再次通过 |
| `node scripts/smoke-m4.mjs` | 5 组幂等、队列顺序、占位、确认/退出递补、任务/历史保护通过 |
| `node scripts/smoke-recovery.mjs` | 4 组真实崩溃、错过期限、重启递补、幂等重放和重复扫描检查通过 |

恢复脚本启动自己的临时 Java 后端（18080），使用独立 `bookflow_test`，确认期限 10 秒。原预约取消后给 B OFFERED，脚本 SIGKILL **自己创建的子进程**；数据库仍保留 OFFERED 和期限。停机跨过期限，重启后 B EXPIRED、下一位 OFFERED，确认成功且只有一个有效占用。旧 Session 失效，重登后同请求键仍重放原结果。再次扫描不产生新记录。结束停止临时进程并清理自己的测试夹具，不停止 8080 开发服务。

本机无凭据结果：`.local/m2-smoke-result.json`、`.local/m4-smoke-result.json`、`.local/recovery-result.json`。恢复服务日志 `.local/recovery-server.log`。需要复现时按 README 准备环境并顺序运行脚本，不能同时运行多个使用同一测试库的套件。

## 网页验收

浏览器实际访问 `http://127.0.0.1:5173/`，用真实演示账号和数据库操作，验证登录、目录、未来时段预约、我的预约、取消历史、退出；M4 验证候补页面、截止时间/倒计时、确认后“已转为预约”。确认资格来自脚本创建的明确标记演示场景，确认操作通过网页发出。

截图：`screenshots/m2-cancelled.png`、`screenshots/m4-offer.png`、`screenshots/m4-confirmed.png`、`screenshots/m4-admin.png`。管理员实际从网页新增 `UI-CHECK-20261010`（明确标记、停用的验收设备），列表显示保存结果，并检查了候补/持久化任务页；浏览器日志未发现 JavaScript 错误或警告。未宣称覆盖所有浏览器/屏幕尺寸或有完整前端自动化测试套件。

## 已修复问题与剩余边界

- 真实测试发现 MyBatis 一级缓存复用旧锁查询状态，已强制锁查询刷新缓存，原失败断言保留并通过。
- 配置模板的演示密码改用随机生成，初始化器拒绝占位前缀，已有账号不会被重启覆盖。
- 后端升级以新 V3/V4 迁移完成，原 V1/V2/demo 校验和保留，已有预约与取消历史仍在。
- 保留 Flyway 对 MySQL 8.4 的“新于其验证版本”提示、Java 21 Mockito 动态 agent 提示。当前项目迁移/测试通过，不表示厂商全面兼容认证。自定义 DaoAuthenticationProvider 的提示来自我们主动绑定数据库 UserDetailsService，不使用默认用户。
- Codex 沙箱内 Mockito attach/本机监听受限；实际验证在获准访问本机服务的工具执行中完成。普通 VS Code 终端按 README 运行。
- 未验证 Windows、跨主机多实例、真实网络分区、数据库主从故障转移、高负载性能和公网部署。多工作线程重复任务已验证，不等于完整分布式部署测试。
- Session 在进程内存，重启需重登；预约、请求、资格、任务持久化。任务为周期扫描，停机期间暂停递补，恢复后补处理。成功幂等键无自动清理；列表尚未分页；前端通知仅站内轮询。这些是明确的当前限制。
