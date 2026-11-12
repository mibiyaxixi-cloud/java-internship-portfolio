# M1 实际进展

更新：2026-10-15（Asia/Shanghai）。本轮只实施 M1。

**M1 代码、真实 MySQL/Qdrant 后端流程和 HTTP 验收完成；Vue 构建通过。浏览器点击验收、Docker Compose 启动、真实模型效果未验证。** 默认显式 MOCK。没有创建其他聊天、子 Agent、远程推送或购买服务；sources、BookFlow、order-reporting 保持原样。

## 已完成

- 独立 Java 21 + Boot 3.5.16 + Spring AI 1.1.8，沿用 MyBatis 3.0.5 + Flyway，依赖兼容依据见 docs/versions.md。前端 Vue 3，提供 Docker Compose 和 .env.example。
- Session + BCrypt + CSRF；ADMIN/READER；仅管理员上传/重试；所有登录读者访问同一组资料；聊天按当前登录用户隔离，管理员也不能读取别人聊天。
- 3 份自编、明确标注模拟的入职指南、设备操作手册、报销规则；UTF-8 Markdown/TXT、256 KiB 上限、章节切块、确定性片段 UUID、原文和元数据入 MySQL。
- PENDING → IMPORTING → INDEXED / FAILED；保存失败阶段与安全原因；Embedding + Qdrant 全部成功再发布；失败可手动重试，不生成重复片段。同名同内容幂等，同名异内容拒绝。
- Qdrant 真正保存向量；查询先取候选，再由 MySQL 检查 available、active_version、INDEXED 和 profile。失效、未发布、错误配置及未知向量不会进模型上下文。
- 模型 JSON 陈述/引用，服务端按本次证据白名单校验；引用缺失/伪造即拒绝整次回答。点击可看文件、章节、版本、文档/片段 ID 和原文，历史引用动态标失效。
- 资料为空时跳过回答模型并明确资料不足；模型与检索调用超时有安全错误。问答失败能保存时记录 FAILED 和耗时；数据库不可用返回503。最多4个同时问答作为基础背压，未宣称完整限流。
- Mock 和 real 是不同适配器，模型/向量配置使用独立 profile / Qdrant 集合。real 缺配置直接失败，不静默回退。无真实模型效果测试。

## 实际执行与结果

命令均从 java-knowledge-assistant（或注明 frontend）执行。首次网络下载/监听在受限环境中失败，使用获准的执行权限完成本机服务验证。完整运行日志保留于忽略的 .local，不对外复制带环境变量的测试 XML。

| 命令或操作 | 实际结果 |
|---|---|
| 读取当前目录、AGENTS.md、已有 pom/Mapper/脚本 | 当前父目录不是 Git 仓库；已有两个项目用 MyBatis；没有修改其文件 |
| `./scripts/backend.sh package` | 首次 Maven DNS 失败；允许联网后依赖解析、Java 编译、13项初始测试及打包成功 |
| `./scripts/backend.sh test` | 曾受 Mockito 动态 attach 限制影响；改用不需要该机制的 subclass mock-maker，测试恢复通过 |
| `MYSQL_BIN=… ./scripts/dev-db.sh run` | 受限环境首次 MySQL 初始化进程异常，目录仍空；获准后独立 MySQL 8.4.11 启动，建立 knowledge / knowledge_test，各自独立账号 |
| `npm install` / `npm install --offline --cache ../.local/npm --no-audit --no-fund` | 网络安装过慢，中止后复制现有包缓存到新项目；离线安装35个包成功，生成 package-lock.json；没有覆盖原项目缓存 |
| `cd frontend && npm run build` | 成功；11模块，主 JS 73.58 kB（gzip 29.19 kB），CSS 5.05 kB；不是浏览器速度指标 |
| 下载 Qdrant v1.15.5 arm64 官方二进制 | 多次超时/DNS失败，分段续传后完成；28,783,681字节，官方SHA-256吻合；证据见 evidence/qdrant-binary.json |
| `./scripts/dev-qdrant.sh` | 真实 Qdrant 1.15.5，独立数据目录，本机16333/16334，API key来自.env |
| `./scripts/backend.sh verify`（第一次） | Qdrant未启动：15单元通过，10流程测试中6失败/1错误；如实保留 .local/integration-first.log |
| `./scripts/backend.sh verify`（最终） | **15单元 + 10真实MySQL/Qdrant流程 + 2本地Spring AI协议夹具 = 27项，0失败/错误/跳过；打包成功** |
| `node scripts/smoke.mjs`（第一次） | Qdrant未启动，实际上传记录FAILED/VECTOR_UPSERT、未发布，HTTP脚本中止 |
| 重启最新后端后 `node scripts/smoke.mjs` | 通过Vue的5175代理执行 **8组HTTP验收通过**；同一文档重试成功；3份资料可检索，引用原文和用户隔离通过，真实multipart超限413 |
| 清理本轮失败的集成夹具 | 仅使用首轮日志明确记录的7个文档ID，限定knowledge_test；开发资料未清理，最终测试库文档数0 |
| `bash -n scripts/*.sh`、`node --check scripts/smoke.mjs` | 通过（shell实际对三个脚本逐个传参检查） |
| 浏览器打开5175 | 工具的管理员策略校验服务不可用，未获准访问；没有绕过、截图或声称点击验收通过 |
| Docker镜像清单查询 | registry / Hub网络读取失败；完整版本标签已固定，未拿到多架构digest；本机没有Docker，未运行Compose |

失败日志仅留本机。可交付的简明证据：[测试与运行参数](../evidence/m1-validation.json)、[HTTP验收](../evidence/m1-http-smoke.json)、[导入状态与片段计数](../evidence/import-state.tsv)。不把这些结果计作模型准确率。

## 运行状态与模型边界

最终验收运行环境：macOS 27.0.1（26A434）arm64、Temurin 21.0.12.1+1、Node 24.21.0、npm 11.19.0、MySQL 8.4.11、Qdrant 1.15.5。本机入口5175，后端8082，MySQL13308。服务为本次启动的进程，不承诺退出应用/重启电脑后持续运行；README提供重启方法。账号密码仅在忽略的.env；没有把密码写进交付文档。

文档版本1；chunker=section-v1，600码点、重叠80；Top-K=4，候选16，minScore=0.20（**未校准**）。mock-extract-v1 / mock-bigram-hash-v1，256维。只验证工程链路，mock相似度及摘录不代表语义能力。

| 真实模型评测项 | 状态 | 结果 |
|---|---|---|
| 回答正确性 | 未运行 | |
| 引用实质支持情况 | 未运行 | |
| 资料不足表现 | 未运行 | |
| 真实模型耗时/失败率 | 未运行 | |

缺少：CHAT_BASE_URL、CHAT_API_KEY、CHAT_MODEL实际固定快照；EMBEDDING_BASE_URL、EMBEDDING_API_KEY、EMBEDDING_MODEL及实际输出维度。两项RealAdapterIT只证明Spring AI向**本地HTTP夹具**发送独立端点/密钥和解析响应，不是外部提供方验证。

Flyway 11.7.2 对 MySQL 8.4 提示高于其已测试最高8.1；本机迁移和测试通过，警告没有隐藏，不宣称官方已认证该组合。网络超时配置已实现；提供方真实超时行为、实际语义支持、提示注入鲁棒性、负载测试未评测。

## 下一阶段（本轮未提前实现）

M2：扩至10—20份模拟资料；实现替换/删除接口，新版本完整索引后原子发布，删除先不可用再清理向量，强化并发窗口与历史引用；完善资料不足、错误、限流、分页及聊天隔离回归。当前版本过滤测试是必要的基础边界，不代表完整更新/删除业务已完成。

M3：准备人工核对且分组防近重复泄漏的20开发题＋20锁定测试题；运行可复现的真实模型评测，未跑留空；再完善最终部署/演示/本人复盘。此次没有创建或使用锁定测试题，也没有预填成绩。

本阶段的文档切块、问答路径和故障定位说明见 [M1学习](m1-learning.md)。独立20分钟练习：给引用详情增加Unicode码点字符数，并验证“中文😀”=3；练习未代做。
