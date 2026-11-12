# 门店订单与经营报表后台

面向 Java 实习作品集的学习项目。**M1—M3 功能、数据库验证和查询分析已完成：CSV 事务导入、门店权限、报表/下钻/导出、10 万条模拟明细与索引实测。** 浏览器交互验收仍因工具管理策略校验故障待补，不能把后端和构建通过视为已经验证图表点击与下载。

入口：[本地网页](http://127.0.0.1:5174)。说明：[PLAN](PLAN.md)、[STATUS](STATUS.md)、[M1 验证](docs/m1-validation.md)、[M2 验证](docs/m2-validation.md)、[模型与统计口径](docs/design.md)、[接口](docs/api.md)、[M1 学习](docs/m1-learning.md)、[M2 SQL、数据流与练习](docs/m2-learning.md)、[版本](docs/versions.md)。M3：[验收](docs/m3-validation.md)、[SQL 性能证据](docs/performance.md)、[学习练习](docs/m3-learning.md)、[演示稿](docs/demo.md)、[简历草稿](docs/resume-draft.md)。

## 启动（macOS/Linux）

从 **order-reporting 目录本身**启动。需要已有 Java 21、MySQL 8.4 和 Node 20.19+ / 22.12+；Maven Wrapper 固定 3.9.11，无需全局 Maven。脚本不会安装 Docker 或 MySQL。

```bash
java -version
mysql --version
node --version
# 终端一：首次生成本项目 .env、随机密码和隔离数据库；请先不要复制 .env.example。
./scripts/dev-db.sh run
# 终端二：自动读取 .env，Flyway 建表，demo profile 创建缺少的演示账号/门店/商品。
./scripts/backend.sh run
# 终端三：前端
cd frontend
npm ci
npm run dev
```

默认端口为 MySQL `13307`、后端 `8081`、前端 `5174`，仅监听本机。MySQL 数据存于本目录 `.local/mysql/data`，Maven 和 npm 缓存亦在本项目；不会读写 BookFlow 数据库或数据目录。依赖缓存首次从已有本地 Maven 缓存复制，之后独立使用，不需要 BookFlow 才能运行。

本机使用 PATH 中的 Temurin 21。若你的 `java` 不在 PATH，设置自己的 `JAVA_HOME`；macOS `/usr/libexec/java_home` 可能找不到未注册的 JDK。MySQL 二进制不在 PATH 时可设置 `MYSQL_BIN=/你的/mysql/bin`。

默认创建 `order_reporting` / `order_reporting_test` 两个独立库与独立账号，仅有各自库权限。库不存在才建表，不清库。`./scripts/dev-db.sh status` 检查，`stop` 只停止该实例并保留文件；前台 `run` 退出也会停止。普通终端可用 `start` 后台运行。初始化失败保留日志与数据；不要删除目录重试。已有 `.env` 时不自动覆盖。

若使用自己已有 MySQL：由管理员执行 [建库 SQL](database/create-databases.sql)，创建两个不同密码的账号，分别授权 `order_reporting.*` / `order_reporting_test.*`（Flyway 需要 DDL 权限）。再复制 `.env.example` 到 `.env` 并填写实际端口与密码。绝不要把应用连接到 root 或 BookFlow 库。这里没有自动连接已有 MySQL 的逻辑。

## 演示

账号 `admin`、`store1`、`store2` 的密码分别是本机 `.env` 中 `DEMO_ADMIN_PASSWORD`、`DEMO_STORE1_PASSWORD`、`DEMO_STORE2_PASSWORD`。不要把密码提交、截图或写入简历。账号密码只在首次创建时使用；修改环境变量不会重置已有账号。

1. 管理员登录，打开“数据导入”，选择 [demo-orders.csv](database/demo-orders.csv)。
2. 预检查应识别 5 条明细、4 笔订单，确认后整批提交。
3. 再传原文件，显示原成功批次；不会再次插入订单。
4. 在订单台账查看 `DEMO-A`：2 行合计 100 元，9 月 30 日支付、10 月 1 日全额退款。
5. 用 `store1` 登录，仅可访问 CS01；上传含 CS02 的完整演示文件会整批拒绝。
6. 在“经营报表”点“演示日期”：两日销售 200 元、退款 100 元、净额 100 元。10/1 净额 -40 元；可点日/门店/商品图形或下方表格按钮下钻。
7. 在资金事件明细切换“退款”，查看 DEMO-A 两行共 100 元，再点“原始订单”。导出此范围 CSV 核对 refund_yuan；台账也支持导出，但原金额不是净销售。
8. 复制演示 CSV 到自己的新文件，改订单号并故意写一行三位小数，查看错误行号；修复后再上传。不要修改已保存订单的内容后试图覆盖。

`node scripts/smoke.mjs` 会自动验证上述 HTTP 路径，显式导入固定演示文件；重复运行幂等，不删除业务数据。它还验证真实 multipart 大小限制、批次归属及门店权限。结果保存在 `.local/smoke-result.json`。它不是 SQL 性能测试。

M2 启动验收：`API_BASE=http://127.0.0.1:5174/api node scripts/smoke-m2.mjs`。要求先导入演示文件且样例日期没有额外订单；只读订单，正常记录导出日志，结果在 `.local/smoke-m2-result.json`。独立手算夹具与权限/错误回归在测试库执行，不受开发数据影响。

## CSV 约定

UTF-8（允许 BOM）、RFC 4180 引号规则；固定表头、最多 1 MiB 与 5000 条数据记录（不含表头）。只接受 CSV，不支持 Excel。行错误最多展示 100 条，行号为记录在文件中的物理起始行，表头为第 1 行。预检查返回全部或前 100 个错误，没有业务写入；确认再次校验保存的原文件。

```csv
store_code,order_no,status,ordered_at,paid_at,refunded_at,order_total,line_no,product_code,quantity,unit_price,line_total
CS01,DEMO-NEW,PAID,2026-10-01T09:00:00,2026-10-01T09:01:00,,60.00,1,TEA,2,30.00,60.00
```

同一订单各行的状态、时间、总额必须相同。`line_no` 在同一订单内唯一；允许相同商品出现于不同明细号。金额以元输入，最多两位小数、必须大于 0；内部转为 long 整数分。无折扣分摊和零价赠品。数量 1—10000、行号 1—5000、单价最多 100 万元、整单最多 10 亿元。订单号 1—64 字符，支持逗号/双引号，禁止控制字符和首尾空格。日期为上海本地时间 `yyyy-MM-ddTHH:mm:ss`，年份 2000—2100。

导入是**不可变的订单快照**：`REFUNDED` 行必须包含原支付时间和全额退款时间；`CANCELLED` 不得有支付/退款时间。不能先导入 PAID，再用 REFUNDED 文件覆盖同号订单。首版没有真实支付、退款操作、历史覆盖或部分退款。

幂等有两层：同账号＋文件原始字节 SHA-256 返回原批次；门店＋订单号的唯一约束防止跨文件/跨账号重复入库。语义相同但换行/行序不同的文件产生新批次，逐单比对规范化哈希后跳过相同内容；不同内容明确报错。原批次的新增/跳过数量是当时结果，重放时不会归零。

## 报表与导出约定

报表按上海支付/退款事件日期（必填，最多 366 日），不会按当前 REFUNDED 状态抹掉原销售。取消订单不计资金事件。金额内部整数分；商品用明细金额汇总，总览不会 JOIN 多条明细重复累加整单金额。商品排行只显示净额前 20 名，图表旁有同值数据表和下钻入口。

导出与当前**已应用筛选**一致，最多 10,000 数据行，每页读取 500 行、单文件最多 16 MiB，超限明确拒绝。CSV 有 UTF-8 BOM、RFC4180 引号转义，并将可能触发公式的用户文本前缀单引号；可信金额仍是数值。该文件用于查看和对账，不是原 CSV 导入模板；表格软件二次保存/重开可能重新解释文本，防护边界见接口文档。

每个统计/下钻/导出请求内部使用同一数据库快照。不同请求之间发生新导入时结果可变化，应刷新报表后核对。台账导出按下单日与当前状态，金额是原始金额；资金事件导出按事件日，净额用于报表对账，两种含义在页面与文件列名区分。

## M3 查询分析复现

```bash
# 项目根目录；独立测试数据库启动后运行，不与 verify 并行
./scripts/benchmark.sh
```

固定公式生成 20 个 CSV（每文件 5,000 行、低于 1 MiB），通过正常预检查/确认导入 50,000 单、100,000 明细。只使用 order_reporting_test；遇到既有 BENCH_M3_ 对象拒绝，正常结束仅清理本次夹具。CSV、哈希、SQL、EXPLAIN/ANALYZE 和所有计时保存在新的 `.local/benchmark/时间/`。不用向开发库上传这些测试文件。

实测支持 Flyway V3 增加 `(store_id,status,ordered_at,id)`：本机全年稀疏退款分页，首轮实际扫描 4,173 → 20 行，20 次暖缓存 JDBC 中位 3.31 → 0.19 ms；仅代表此指定查询，非线上容量。迁移后复现通过 IGNORE INDEX 做对照，不删除既有索引。两轮生成文件哈希一致。完整方法、局限见 performance.md。

演示与面试准备见 demo.md/resume-draft.md；本项目属于个人学习作品，没有真实支付或生产用户规模。

## 验证与边界

```bash
# 4 个单元检查：测试库保护、CSV 文本防护/大小上限
./scripts/backend.sh test
# 真实 MySQL 集成验证 + 单元测试 + 打包
./scripts/backend.sh verify
cd frontend
npm run build
```

当前完整回归为 **4 项单元测试 + 31 项真实 MySQL 集成测试**，基准探针独立执行、不计入这 35 项。

测试在应用上下文/Flyway 初始化前检查 JDBC 数据库名必须恰为 `order_reporting_test`，使用 `TEST_DB_*`，不使用 H2。每次生成独有门店、账号和订单，结束只清理自己创建的记录；业务事务测试是真实提交/回滚。故障测试在测试表上临时添加只拒绝本次随机订单号的 CHECK，最后删除该约束。不要并行执行多个验收套件。没有 Flyway clean、TRUNCATE 或开发库清理。

限制：同步小文件导入，单业务事务超时 30 秒；原文件与错误在库中保留，无自动清理。成功事务不会留半张订单；若进程恰在业务回滚后、独立失败记录提交前被杀死，批次可能仍为 READY；重试确认会重新校验。数据库完全不可用时无法保证立即保存失败，接口明确返回 503，恢复后查询并重试。没有宣称实现崩溃后自动恢复或生产可用性保证。

登录为 Session + CSRF，所有写请求必须携带 CSRF，30 分钟无活动过期，重启需重新登录。账号/门店管理、生产部署、限流均不在当前阶段。Flyway 11.7.2 对 MySQL 8.4 有兼容性提示，保留该日志；本项目迁移与测试实测通过，详见版本说明。

Windows 可用 `mvnw.cmd -Pmysql-it verify`，需在 PowerShell 显式设置 `.env.example` 中的环境变量；Bash 辅助脚本未在 Windows 验证。
