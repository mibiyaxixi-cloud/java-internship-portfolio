# M1 实际验证记录

执行日期：2026-10-09，macOS arm64。所有结论来自本次实际执行，不代表生产经历或性能测试。

## 环境

- Java / javac：Temurin 21.0.12.1。
- Node / npm：24.21.0 / 11.19.0。
- MySQL：8.4.11，使用已有二进制，本项目独立数据目录 `.local/mysql/data`，监听 `127.0.0.1:13306`。
- Maven：Wrapper 3.3.4 下载并运行 3.9.11；没有依赖全局 Maven。
- 数据库：应用库 `bookflow` 与测试库 `bookflow_test` 使用不同账号；测试启动前校验库名必须以 `_test` 结尾。
- 项目初始目录无源码、无 Git 仓库；源码新建在 `bookflow/`，未改动上层 `AGENTS.md` 和 `sources/`。

## 已执行结果

| 检查 | 命令 / 方式 | 结果 |
| --- | --- | --- |
| 后端单元测试 | `./scripts/backend.sh test` | 4 个通过，0 失败、0 跳过 |
| 完整后端验证与打包 | `./scripts/backend.sh verify` | 4 个单元测试 + 9 个真实 MySQL 集成测试全部通过，BUILD SUCCESS |
| Vue 生产构建 | `npm run build`（frontend 目录） | 通过，生成 dist |
| 首次启动 | `./scripts/backend.sh run` | 成功监听 8080，Flyway 对新应用库应用 V1 和显式 demo 种子 |
| 真实 HTTP | `node scripts/smoke.mjs` | 6 项检查全部通过 |
| 实际数据库故障 | 停止本项目 MySQL 后，`node scripts/smoke.mjs --expect-db-down` | 设备列表和时段查询都返回 503 / DATABASE_UNAVAILABLE，只含 code/message |
| 实际数据库恢复 | 重启 MySQL，再执行正常 smoke | 6 项再次通过，Spring Boot 未重启 |
| Bash / JavaScript 语法 | bash -n；node --check | 通过 |
| 浏览器 UI | 尝试使用浏览器工具打开 5173 | 未完成，见下方明确限制 |

本地自动化证据（构建输出，不提交 Git）：

- `.local/m1-verify.log`：最后一轮完整成功日志。
- `backend/target/surefire-reports/com.bookflow.catalog.CatalogServiceTest.txt`。
- `backend/target/failsafe-reports/com.bookflow.catalog.CatalogMySqlIT.txt`。
- `backend/target/bookflow-backend-0.1.0.jar`：可执行后端构件。
- `frontend/dist/`：前端生产构件。

## 测试覆盖与边界

单元测试验证开始/结束精确边界、关闭和占用状态、上海当天日期与查询半开区间，以及非法参数/不存在设备。

真实 MySQL 集成测试验证：

1. 连接的产品确实是 MySQL；Flyway 校验及再次 migrate 不重复应用 V1。
2. 设备接口从数据库读取，修改数据库后接口反映新值。
3. 时段按设备/日期筛选、按时间排序，有效占用从预约表查询。
4. 存在设备的空日程与不存在设备分别为 200/404。
5. 非法日期、非正/非数字 ID 为 400。
6. M1 不公开写入或管理端点。
7. 同一时段只能一条有效预约，同时允许多条取消历史及取消后新的有效记录。
8. 数据库拒绝倒置时间区间和删除被引用设备/时段。
9. demo profile 未启用时没有演示记录；同一演示 SQL 执行两次仍为 3 台设备、42 个时段。

数据库测试用 MockMvc 驱动真实 Spring MVC / Security / Service / MyBatis / MySQL 链路；HTTP smoke 另外访问真正监听端口的 Tomcat。测试事务在结束时回滚测试数据，保留迁移结构。测试中的用户禁用且无可用登录密码。以上不等于已经实现预约接口、登录角色鉴权或并发业务测试。

## 发现并修复的问题

- **沙箱限制**：初次网络下载无法解析域名，本地监听被拒绝，MySQL 初始化出现内存映射权限错误。获准后在沙箱外运行必要下载与本机服务；未改动其他项目的数据库。
- **后台进程生命周期**：执行环境回收命令结束后的 MySQL 子进程，增加 `dev-db.sh run` 保持前台会话；普通终端仍可用 start/stop。
- **Bash 中文相邻变量**：提示文案中的 `$PORT` 紧接中文标点被旧 Bash 误解析，改为 `${PORT}`。
- **命名时区表缺失**：JDBC 强制把会话时区设为 Asia/Shanghai 导致新实例连接失败。保留 JDBC 的时区映射，在 Hikari 初始化时用 `SET time_zone='+08:00'`，不要求预装 MySQL 命名时区表。
- **CHECK 错误映射差异**：MySQL 3819 / HY000 被 Spring 包装为 UncategorizedSQLException；测试改为断言底层错误码 3819 和约束名，仍严格验证数据库拒绝非法时段。
- **测试会话缓存**：两次 MockMvc 调用共享测试事务，原生 JDBC 更新不会使 MyBatis 一级缓存失效。测试中明确清除 SqlSession 缓存来模拟下一次独立 HTTP 请求，保留更新后 JSON 必须变化的断言。
- **事务创建失败的错误边界**：时段查询在开始事务时也可能因数据库不可达而失败，异常处理同时覆盖 DataAccessException 和 CannotCreateTransactionException；实际停库验证两个端点都返回 503。

Flyway 对 MySQL 8.4 的版本建议升级日志及 Mockito 的 Java agent 提示仍存在，没有屏蔽；适用边界和版本理由见 `versions.md`。这些提示没有导致本次测试失败。

## 尚未验证

**浏览器交互和视觉验收未完成。** 浏览器工具返回：管理员强制安全策略无法校验，因此拒绝访问 `http://127.0.0.1:5173`。未尝试绕过该限制，也没有使用截图或端到端浏览器测试来冒充已验证。Vite 已启动，生产构建已通过；这不能替代真实点击、布局和可访问性检查。

请在本机浏览器手动检查（所有服务按 README 启动）：

- [ ] 打开 http://127.0.0.1:5173，出现“只读预览 · M1”和 3 台演示设备。
- [ ] 选择显微镜/离心机，当天各显示 3 个时段；时间按当前状态显示开放/已开始/已结束。
- [ ] 选择停用的 PCR 仪，出现停用说明和空日程；切换无种子日期也显示空状态。
- [ ] 搜索不存在的关键词，出现搜索空状态；清除搜索后恢复设备。
- [ ] 快速切换设备与日期，不显示旧选择的异步结果。
- [ ] 停止后端后刷新设备，出现错误与重试；恢复后端后重试恢复。查看浏览器 Network 确认请求为 `/api`。
- [ ] 窄窗口/手机宽度、键盘 Tab 焦点和日期选择器可用，无横向溢出。

Windows 运行命令、其他机器的全新安装、VS Code F5 和浏览器断点操作提供了配置/步骤，但本次未通过 GUI 实测。未部署公网，未实现或声称验证 M2/M3/M4，也未执行性能测试。
