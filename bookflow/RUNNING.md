# BookFlow

实验室设备预约与候补调度平台，面向 Java 后端学习与实习作品集。

**当前实现 M1～M4：登录、预约、取消、历史、管理员维护、请求幂等、可靠候补与超时递补。** 页面通过 Spring Boot / MyBatis 访问真实 MySQL。采用数据库事务、行锁、唯一约束和持久化调度任务；无需 Redis/消息队列。完整验证结果见 [最终验收记录](docs/validation.md)。原计划没有 M5；公网部署仍是独立任务。

## 目录与版本

```text
bookflow/
├── backend/                  Spring Boot 单体，按 catalog/auth/reservation/admin/idempotency/waitlist 等功能组织
│   └── src/main/resources/db/
│       ├── migration/        正式结构迁移
│       └── demo/             显式启用的演示数据
├── frontend/                 Vue 3 + Vite + JavaScript
├── database/                 创建空数据库的 SQL
├── scripts/                  本地启动和验证脚本
├── docs/                     阶段计划、设计、学习和验证记录
├── .vscode/                  推荐扩展、运行与调试配置
├── .env.example              配置模板，没有真实密码
├── .mvn/wrapper/              Wrapper 3.3.4，Maven 3.9.11
├── mvnw / mvnw.cmd            无需全局安装 Maven
└── pom.xml                   同仓库 Maven 父工程
```

| 组件 | 固定版本 / 规则 |
| --- | --- |
| Java | 21；本机实测 Temurin 21.0.12.1 |
| Spring Boot | 3.5.16 |
| MyBatis Spring Boot Starter | 3.0.5 |
| Maven / Wrapper | 3.9.11 / 3.3.4 |
| MySQL Server | 本机 8.4.11，目标 8.4 LTS |
| Vue / Vite / Vue 插件 | 3.5.43 / 8.3.4 / 6.0.9 |
| Node / npm | 本机 24.21.0 / 11.19.0；Vite 需要 Node 20.19+ 或 22.12+ |
| Spring Security、Flyway、MySQL JDBC、JUnit 等 | 由固定的 Spring Boot 3.5.16 BOM 管理；解析版本见 docs/versions.md |

所有 npm 直接依赖使用精确版本，传递依赖锁在 `frontend/package-lock.json`。不自行拼装不同代 Spring 组件，不使用 H2 替代 MySQL。当前页面使用少量原生 CSS，未引入 Element Plus，方便先理解 Vue 和请求流程。

## 首次运行（VS Code，macOS/Linux）

用 VS Code **打开 bookflow 文件夹本身**，不要把上层 ChatGPT 镜像当成源码仓库。先检查：

```bash
java -version
javac -version
node --version
npm --version
mysql --version
```

### 1. 准备数据库，两种方式任选其一

**方式 A：本机已有 MySQL 二进制，创建隔离的开发实例。**

首次运行前不需要复制 `.env.example`。脚本会创建 `.env` 和随机密码，默认使用 `127.0.0.1:13306`，数据库名为 `bookflow` / `bookflow_test`，数据放在 `.local/mysql/data`，不操作其他 MySQL 实例。

```bash
# 若 mysqld/mysql 不在 PATH，先设置二进制目录：
# export MYSQL_BIN=/absolute/path/to/mysql/bin
./scripts/dev-db.sh run
```

保持这个终端运行。普通终端也可用 `./scripts/dev-db.sh start` 后台启动；使用本项目 socket 的 `./scripts/dev-db.sh status` 检查，`./scripts/dev-db.sh stop` 停止并保留数据。脚本不会自动下载 MySQL。首次初始化若中断会保留现场，查看 `.local/mysql/mysql.log`，不要直接删除目录重试。已有 `.env` 时脚本拒绝新建，避免覆盖配置。

**方式 B：使用自己已经运行的 MySQL 8.4。**

用管理员执行 `database/create-databases.sql`（按需改端口），再在 MySQL 客户端创建专用账号：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p < database/create-databases.sql
mysql -h 127.0.0.1 -P 3306 -u root -p
```

```sql
-- 将下列两个密码替换为自己的密码，仅本地开发使用。
CREATE USER 'bookflow'@'127.0.0.1' IDENTIFIED BY 'replace_with_local_password';
CREATE USER 'bookflow_test'@'127.0.0.1' IDENTIFIED BY 'replace_with_test_password';
GRANT ALL PRIVILEGES ON bookflow.* TO 'bookflow'@'127.0.0.1';
GRANT ALL PRIVILEGES ON bookflow_test.* TO 'bookflow_test'@'127.0.0.1';
```

```bash
cp .env.example .env
```

编辑 `.env`，将 URL 端口改为你的端口，填写两个账号密码。业务账号仅拥有各自数据库权限；为便于本地 Flyway 建表，开发账号包含 DDL 权限。不要让应用连接 root。未提供数据库配置时启动会失败，不会悄悄切到内存数据库。

### 2. 准备演示账号并启动后端（新终端，在 bookflow 根目录）

```bash
# 也适用于从 M1 升级；只追加缺少的随机密码，不覆盖已有配置。
node scripts/prepare-demo.mjs
./scripts/backend.sh run
```

脚本读取 `.env`，Wrapper 首次联网下载 Maven 与依赖，并将缓存放在 `.local/maven`。Flyway 启动时自动执行迁移。默认 API：`http://127.0.0.1:8080`。启动成功并完成演示账号初始化后再启动网页；如配置缺失会明确报错并退出。

本机 Java 是一个未注册到 macOS 的 JDK，所以 `/usr/libexec/java_home -V` 可能找不到它。已在本机忽略的 `.env` / `.vscode/settings.json` 写入实际路径。换电脑后使用自己的 Java 21 安装目录设置 `JAVA_HOME`，不要复制他人的路径。

### 3. 启动前端（再开一个终端）

```bash
cd frontend
npm ci
npm run dev
```

浏览器打开 [BookFlow 本地网页](http://127.0.0.1:5173)。请求 `/api` 经 Vite 代理到后端，页面没有硬编码设备列表。三个设备中两台开放、一台停用；首次演示种子给两台设备各生成当天起七天、每天三个时段。

修改后端端口时，也要给 Vite 设置 `API_PROXY_TARGET=http://127.0.0.1:你的端口`。开发服务器与数据库默认仅本机可访问。

### Windows

推荐先使用 MySQL 安装程序/已有服务按方式 B 建库；Bash 便利脚本未在 Windows 验证。Wrapper 提供 `mvnw.cmd`。在 PowerShell 中显式设置环境变量（Spring Boot 不自动读取 `.env`）：

```powershell
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/bookflow?connectionTimeZone=Asia/Shanghai'
$env:DB_USERNAME='bookflow'
$env:DB_PASSWORD='填写本机密码'
$env:SPRING_PROFILES_ACTIVE='demo'
# 填入 12～72 UTF-8 字节的不同随机密码；不要使用下面的说明文字。
$env:DEMO_USER_PASSWORD='填写普通用户随机密码'
$env:DEMO_SECOND_USER_PASSWORD='填写第二位用户随机密码'
$env:DEMO_ADMIN_PASSWORD='填写管理员随机密码'
.\mvnw.cmd -pl backend spring-boot:run
```

另一个终端执行 `cd frontend; npm ci; npm run dev`。运行数据库测试前，另设 `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` 并指向以 `_test` 结尾的独立库。

## 演示数据与迁移

- 正式结构在 `backend/src/main/resources/db/migration/V1__create_core_tables.sql`，后续迁移依次为 V2 演示用户标记、V3 幂等请求、V4 候补/限时占位/任务。已有数据库直接启动按序迁移，保留原数据。
- `SPRING_PROFILES_ACTIVE=demo` 才加载 `db/demo/R__demo_catalog.sql`。所有演示设备/时段均 `is_demo=true`，网页显示演示标记。M2 的 `DemoAccountInitializer` 只创建缺少的演示账号：`student`（USER）、`student2`（USER）、`admin`（ADMIN）；对应密码依次来自 `.env` 的 `DEMO_USER_PASSWORD`、`DEMO_SECOND_USER_PASSWORD`、`DEMO_ADMIN_PASSWORD`。页面显示“演示”。请在编辑器中查看本机密码，不要提交或截入演示录像。没有预置预约。
- 版本化迁移每个版本只应用一次；Flyway 将校验和记录到 `flyway_schema_history`。后续变更新建 `V5__...sql`，不要修改已应用的 V1～V4。迁移版本 V5 不代表新增项目里程碑 M5。
- 重复启动不重复插入，不覆盖已有账号密码/角色，也不移动过去的演示时段。修改环境变量不会重置数据库中已经创建的密码。Repeatable 迁移在内容改变时重跑，并非每次启动重跑。
- 几天后需要新时段时，可**显式重复执行同一演示 SQL**，只追加缺少的未来七天时段。它不删除或修改已有记录，也不覆盖预约历史：

```bash
# 在仓库根目录运行；在密码提示中填写 .env 中的 DB_PASSWORD。
mysql -h 127.0.0.1 -P 13306 -u bookflow -p bookflow --init-command="SET time_zone='+08:00'" < backend/src/main/resources/db/demo/R__demo_catalog.sql
```

关闭 demo profile 不会删除已写入的数据。在一个曾应用 demo 迁移的库上请保持该 profile；若要验证纯空库，创建一个新的数据库，使用不启用 demo 的配置启动。没有自动清库按钮。

## 接口调用

```bash
curl -i http://127.0.0.1:8080/api/devices
# 先取设备 ID，下面以首次建库的 1 为例。
curl -i 'http://127.0.0.1:8080/api/devices/1/slots'
curl -i 'http://127.0.0.1:8080/api/devices/1/slots?date=2026-10-09'
# 参数错误：400；不存在：404；未登录读取受保护资源：401；已登录无权限：403。
curl -i 'http://127.0.0.1:8080/api/devices/1/slots?date=not-a-date'
curl -i 'http://127.0.0.1:8080/api/devices/999999/slots'
curl -i http://127.0.0.1:8080/api/admin/reservations
```

设备接口返回 JSON 数组。时段接口返回：

```json
{
  "deviceId": 1,
  "date": "2026-10-09",
  "timeZone": "Asia/Shanghai",
  "slots": [
    {"id": 1, "startAt": "2026-10-09T09:00:00", "endAt": "2026-10-09T10:30:00", "availability": "AVAILABLE", "demo": true}
  ]
}
```

示例仅说明结构，实际 ID/日期/状态来自数据库和查询时间。日期默认是上海时区当天，日期范围 1000～9998 年；按**开始日期**查询，排序按开始时间和 ID。返回所选日期的全部预设时段，状态可为 `AVAILABLE`（开放）、`OCCUPIED`（已占用）、`CLOSED`（未开放）、`STARTED`（已开始）、`ENDED`（已结束）。已有设备无时段返回 `200` 和空数组，不存在的设备返回 `404`。数据库查询失败返回 `503` 与 JSON 错误，不用空数组掩盖异常。

### 登录与写入接口

| 方法与路径 | 权限 / 用途 |
| --- | --- |
| GET `/api/auth/csrf` | 匿名可用，返回当前会话的 `headerName`、`token` |
| POST `/api/auth/login` | 表单 `username/password`，携带 CSRF，返回当前用户 |
| GET `/api/auth/me` | 当前登录用户 |
| POST `/api/auth/logout` | CSRF + Session，返回 204 |
| GET `/api/reservations/mine` | 本人预约，后端按登录用户过滤 |
| POST `/api/reservations` | JSON `{ "slotId": 123 }`，必须带 `Idempotency-Key`，成功 201 |
| POST `/api/reservations/{id}/cancel` | 仅本人，返回 200；重复取消返回原状态 |
| GET `/api/admin/reservations` | ADMIN，全部预约历史 |
| POST `/api/admin/devices` | ADMIN，新增设备 |
| PUT / DELETE `/api/admin/devices/{id}` | ADMIN，修改/删除设备 |
| GET / POST `/api/admin/devices/{id}/slots` | ADMIN，按 `?date=YYYY-MM-DD` 查询 / 新增时段 |
| PUT / DELETE `/api/admin/slots/{id}` | ADMIN，修改/删除时段 |
| GET `/api/waitlist/mine` | 本人候补、顺位、确认期限、关联预约 |
| POST `/api/waitlist` | `{ "slotId": 123 }`，必须带 `Idempotency-Key`，满额加入候补 |
| POST `/api/waitlist/{id}/confirm` | 本人确认资格，必须带 `Idempotency-Key` |
| POST `/api/waitlist/{id}/withdraw` | 本人退出队列/放弃资格，保留历史 |
| GET `/api/admin/waitlist` | ADMIN 查看所有候补历史 |
| GET `/api/admin/waitlist/tasks` | ADMIN 查看持久化任务和连续失败计数 |

所有写操作都需 CSRF；缺少凭证会优先返回 403，即使尚未登录。成功响应不包含密码哈希。冲突返回 409，非法参数 400，数据库临时不可用 503，统一错误结构为 `{ "code": "...", "message": "..." }`。

下面在仓库根目录用 Bash 演示真实登录和预约。先从公开时段接口选一个**未来且开放**的 `slotId`，替换 `SLOT_ID`。Cookie/临时凭证只保存在被忽略的 `.local` 中；不要使用 `set -x` 或分享这些文件。

```bash
set -a
source .env
set +a
API=http://127.0.0.1:8080
mkdir -p .local
umask 077
COOKIE=.local/curl-cookie.txt
# 登录前获取 token；curl 的 -b / -c 同时读取和更新 Session Cookie。
get_csrf() {
  curl -fsS -b "$COOKIE" -c "$COOKIE" "$API/api/auth/csrf" > .local/csrf.json
  CSRF_HEADER=$(node -p 'const t=require("./.local/csrf.json"); t.headerName + ": " + t.token')
}
get_csrf
# 密码经标准输入传入 curl，不写到 URL 或 curl 的命令行参数。
printf '%s' "$DEMO_USER_PASSWORD" | curl -i -b "$COOKIE" -c "$COOKIE" \
  -H "$CSRF_HEADER" --data-urlencode 'username=student' --data-urlencode 'password@-' "$API/api/auth/login"
curl -fsS -b "$COOKIE" "$API/api/auth/me"
# 登录会更新会话/CSRF，因此写入前重新获取 token。
get_csrf
SLOT_ID=123  # 替换为真实可预约的时段 ID
REQUEST_KEY=$(node -p 'crypto.randomUUID()')
curl -i -b "$COOKIE" -c "$COOKIE" -H "$CSRF_HEADER" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $REQUEST_KEY" -d "{\"slotId\":$SLOT_ID}" "$API/api/reservations"
curl -fsS -b "$COOKIE" "$API/api/reservations/mine"
# 将 456 替换为刚创建的 reservation.id；客户端不能指定 userId。
get_csrf
curl -i -b "$COOKIE" -c "$COOKIE" -H "$CSRF_HEADER" -X POST "$API/api/reservations/456/cancel"
get_csrf
curl -i -b "$COOKIE" -c "$COOKIE" -H "$CSRF_HEADER" -X POST "$API/api/auth/logout"
```

管理员设备请求体：`code/name/category/location/description/status`，状态为 `ACTIVE` 或 `INACTIVE`；时段请求体：`startAt/endAt/status`，例如 `2030-06-01T09:00:00`、`2030-06-01T10:00:00`、`OPEN`，请自行选实际未来日期。JSON 不接受未知字段，防止把客户端 `userId` 误当可信身份。

### 业务规则

- 开始前可预约、取消；达到开始时间后两者均拒绝。取消保留历史；再次预约生成新 ID。
- 同一时段最多一条 CONFIRMED 或 HELD 占位。新的请求争用已满时段返回 409；同一用户、相同操作/资源/幂等键的成功重试返回原状态码和原响应，响应头 `Idempotency-Replayed: true`。取消后重新预约必须使用新键。
- 管理员可管理设备/时段、查看全部记录，也可预约本人使用；不能取消他人的预约。
- 设备编号与时段所属设备创建后不可改；时段有任何预约或候补历史就不能改时间或删除。已开始时段不能编辑；无历史时段可删除。
- 有效预约或限时占位阻止关闭时段；设备存在任何未结束预约、限时占位或进行中候补时不能停用。设备有关联时段时不能删除。相邻时段允许，任何状态的重叠时段拒绝。
- 名称/位置可修订，历史记录展示当前设备资料。会话 30 分钟无活动过期；后端重启需重新登录，预约记录仍在 MySQL。

### 候补与可恢复请求

名额满时点击“加入候补”，在“我的候补”查看顺位。顺序按后端持有设备锁后分配的入队 ID 升序，退出后再入队排到队尾。取消预约后，下一位立即获得 HELD 资格；默认 15 分钟内确认，最晚不超过时段开始。资格期间其他人不能直接预约。确认转为 CONFIRMED；超时转 EXPIRED 并递补；主动放弃也会递补。时段开始后所有未完成候补结束。

页面每 5 秒刷新候补并显示截止时间，没有邮件/短信通知。前端倒计时只作提示，以后端持锁后的时间校验为准。确认和超时在同一设备/时段锁下互斥。管理员“候补与任务”页可查看处理状态。重启后调度器扫描数据库中的到期任务，不依赖内存倒计时恢复。

可在 `.env` 配置，修改后重启后端：

```dotenv
WAITLIST_OFFER_SECONDS=900
WAITLIST_SCAN_DELAY_MS=5000
WAITLIST_SCHEDULING_ENABLED=true
```

本地演示超时可将确认期限改为 30 秒；已发出的资格继续使用原数据库期限。关闭扫描仅供故障实验，恢复为 true 后重启会补处理；不要把长时间关掉扫描的状态当正常运行。

幂等键为 16～80 位字母/数字/横线/下划线，建议 UUID。预约、加入候补、确认资格需要该头。相同键改操作或资源返回 409；键按用户隔离。成功响应与业务同事务持久化，失败事务整体回滚，失败请求可以重新尝试。重放的是**当时的成功快照**，不是记录现在的状态，当前状态应 GET 查询。成功键目前不自动清理。

网页在 sessionStorage 中按用户、操作和资源保存未确定请求的随机键，网络/服务失败后再次提交沿用；明确成功/拒绝后结束此意图。里面没有密码或认证凭据。取消预约后清理该时段的旧意图，重约用新键；清除浏览器数据或换浏览器会丢失客户端未确定键，所以结果不确定时应先查记录。普通取消/退出通过目标记录状态天然重复安全。

## 构建、测试和调试

```bash
# 不需要数据库的 Java 单元测试
./scripts/backend.sh test
# 真实 MySQL 集成测试 + 单元测试 + 可执行 JAR 打包（需要独立测试库）
./scripts/backend.sh verify
# 只打包并运行单元测试（不代表通过 MySQL 集成验证）
./scripts/backend.sh package
# 前端生产构建
cd frontend
npm run build
# 可选：本地预览生产构建，仍需真实后端
npm run preview
```

集成测试不使用 Docker/H2；在 `TEST_DB_URL` 指定的真实 MySQL 上执行，启动前检查数据库名必须以 `_test` 结尾。夹具放在测试事务里，测试结束回滚，Flyway 表结构保留。本次通过 4 项单元测试和 44 项真实 MySQL 集成测试，共 48 项。包含 M1/M2 回归与 M3/M4 的 19 项可靠性测试；并发测试使用独立线程/连接与真实提交，结束后只清理自己创建的 `_test` 库夹具。不要并行运行多个验收脚本或测试套件。结果和边界见 `docs/validation.md`。

VS Code 安装推荐的 Java 扩展包、Spring Boot Tools 和 Vue 扩展，等待 Maven 导入完成。打开 `CatalogController.java`，在 `listSlots` 打断点，选择 **BookFlow: 启动后端** 后按 F5（先关闭终端中已运行的 8080 服务）。也可以执行 `./scripts/backend.sh debug`，再选择 **BookFlow: 附加后端 5005**。网页选择设备触发请求后，观察 `deviceId`、`date`，单步进入 Service/Mapper。调试 Vue 的配置需安装 Chrome。

关闭前后端终端用 Ctrl+C。若以前台模式运行 MySQL，也可从另一终端执行 `./scripts/dev-db.sh stop`。

运行中的后端还可执行 HTTP 冒烟检查（需 demo 数据）：

```bash
node scripts/smoke.mjs
# M2 全流程：创建独立 CHECK-* 验收设备，最终保留两条取消历史并停用设备。
# 会删除脚本自己创建的无历史 TEMP-* 设备/时段；不触碰其他数据。
node scripts/smoke-m2.mjs
# M3/M4 幂等、候补、递补、确认/退出与任务状态的真实 HTTP 验收。
node scripts/smoke-m4.mjs
# 可选：准备一个候补场景，随后用 student2 登录“我的候补”确认。
node scripts/smoke-m4.mjs --prepare-ui
# 先 verify 构建 JAR。独立测试库 + 临时 18080 后端，真实崩溃/重启验证。
# 只终止脚本自己的 Java 子进程；不停止 8080 开发服务；结束清理自己的测试夹具。
node scripts/smoke-recovery.mjs
# 如需手动复现数据库故障，从其他终端停止本项目 MySQL 后执行：
node scripts/smoke.mjs --expect-db-down
# 然后重启 MySQL，再执行正常检查；无需重启后端。
```

恢复脚本需要 `mysql` 客户端（或 `.env` 的 `MYSQL_BIN`）、Java 21（或 `JAVA_HOME`）、`TEST_DB_*` 及已经初始化的 student 演示账号。它只读取该演示账号哈希用于临时测试账号，不输出凭据。需先关闭其他访问同一测试库的验收进程；若 18080 占用可设 `RECOVERY_TEST_PORT`。日志和无密码结果位于 `.local/`。

## 常见问题

- `JAVA_HOME` 无效：填写 Java 21 的 Home 目录；确认终端 `java -version` 和 VS Code 的 Java runtime 一致。
- 依赖下载失败：检查 Maven Central / npm 网络、代理；不要为了绕过错误随意换依赖版本。本次 Codex 沙箱的联网/监听需授权，普通 VS Code 终端无此沙箱限制。
- 数据库连接失败：确认服务、端口、`.env`、账号授权；新建空库由 MySQL 管理员完成，Flyway 负责业务表。
- `Public Key Retrieval is not allowed`：优先使用本机 MySQL 默认 TLS；若自行关闭 TLS，仅可信本地开发可按 MySQL JDBC 文档配置公钥，勿直接套用生产参数。
- 登录失败：确认 demo profile 和初始化日志；查看 `.env` 中对应密码；已有账号不会被环境变量重置。首次从模板复制后运行 `node scripts/prepare-demo.mjs`。
- 写入返回 403：先区分 `CSRF_INVALID` 与 `FORBIDDEN`；前者刷新登录凭证，后者检查角色/记录归属。
- 网页没有时段：查看所选日期，演示种子仅首次初始化的七天；按上述 SQL 显式追加。
- 5173/8080/13306 已占用：不要终止未知进程；选择其他端口并同步 URL/代理设置，隔离数据库脚本端口可在脚本中调整。
- Flyway 显示 MySQL 版本建议升级：固定 BOM 中的 Flyway 会给 MySQL 8.4 提示；本次实际迁移与回归已通过，兼容性边界见 `docs/versions.md`。
- Flyway 校验失败：核对是否修改了已应用迁移，恢复原文件并追加新迁移；不要自动 clean 或删除历史表。

阶段计划见 `docs/plan.md`；数据模型与限制见 `docs/design.md`；完整请求流转、文件导读、练习和面试题见 `docs/learning.md`；M1/M2 阶段材料保留供逐步阅读。本阶段只完成本地运行，没有公网部署或生产使用数据。
