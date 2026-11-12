> 整理提示：本文主要保留 M1 的启动与演示说明。当前代码已进入 M2，差异及未验收项以 [STATUS](STATUS.md) 为准。

# Java 知识库问答助手 · 知页

用于大专学生 Java 开发实习作品集的个人学习项目。当前包含 M1 基础实现与 M2 开发快照：12 份模拟资料、共享检索、引用问答和用户记录隔离。M2 完整验收尚未交付，见当前 STATUS。**默认 MOCK，仅验证工程流程，不代表真实模型效果，也不是训练大模型。** 实际验收与缺口见 [STATUS](STATUS.md)。

技术：Java 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / MyBatis 3.0.5 / MySQL 8.4 / Qdrant 1.15.5 / Spring Security / Vue 3。沿用同目录其他项目的 MyBatis 方案。所有工作位于本目录；未修改 sources、已有项目或用户成果。

## 启动：Docker Compose

需要 Docker Engine / Desktop 和 Compose v2。仓库不安装 Docker 或购买服务。

```bash
cd java-knowledge-assistant
cp .env.example .env
# 编辑 .env：DB_PASSWORD、MYSQL_ROOT_PASSWORD、QDRANT_API_KEY 填随机字符串；
# ADMIN_PASSWORD、READER_PASSWORD、READER2_PASSWORD 各填 12—72 字符密码。
# 可用 openssl rand -hex 24 生成；不要将值写入源码、README 或截图。
docker compose up --build -d
docker compose logs --tail=80 backend
```

打开 [本地页面](http://127.0.0.1:5175)。账号 `admin`、`reader`、`reader2`，密码取自自己的 `.env`。初始化只创建缺少的账号，修改环境变量不会自动重置数据库中已有密码。Compose 仅绑定本机端口，持久卷保留 MySQL/Qdrant 数据；`docker compose down` 停止，勿加 `-v` 误删数据。

完整部署启动情况必须以 STATUS 为准，写了 Compose 不等于已经在本机运行过。

## 启动：已有本机运行时

需要 Java 21、Node 20.19+ / 22.12+、MySQL 8.4、Qdrant 1.15.5。Maven Wrapper 固定 3.9.11。

```bash
# 从本目录执行。可选脚本仅使用已安装的 MySQL，不安装/触碰其他实例。
# 若首次使用 dev-db.sh，不要先复制 .env；它会创建随机密码与独立数据库。
MYSQL_BIN=/你的/mysql/bin ./scripts/dev-db.sh run
# 第二个终端：官方 Qdrant 对应平台二进制置于 .local/qdrant-bin/qdrant
./scripts/dev-qdrant.sh
# 第三个终端
./scripts/backend.sh run
# 第四个终端
cd frontend
npm ci
npm run dev
```

专用 MySQL 端口 13308，后端 8082，Qdrant HTTP 16333，前端 5175。可选数据库脚本创建 `knowledge` 和 `knowledge_test`，各自账号只授权各自库；数据、缓存和日志都在忽略的 `.local`。`dev-db.sh stop` 仅关闭该实例，保留文件。Qdrant 由前台进程 Ctrl+C 关闭。不要并行运行本机和 Compose 来争用相同端口。

已有外部数据库时直接复制 `.env.example`，由数据库管理员建空库 `knowledge` 并授权专用账号；Flyway 自动建表。不要连接其他项目库或 root。Spring Boot 本身不读取 `.env`；本地启动脚本会读取，Compose 通过 env_file 注入。Windows 可使用 Docker Compose；若使用已安装 Maven，需手动设置环境变量；Bash 脚本仅面向 macOS/Linux，Windows 启动未验收。

## 三分钟体验

1. admin 登录，进入“共享资料”，逐一上传 `samples/01-onboarding.md`、`02-equipment.md`、`03-expenses.txt`。三份都明确标注模拟，状态须为可检索。
2. reader 登录，问“模拟网约车单次报销上限是多少？”。mock 显示原文摘录与醒目标识。
3. 点引用，核对文件、版本、章节、片段 ID 和原文，再点完整文件。页面用文本渲染，文档中的 HTML 不执行。
4. reader2 登录，看不到 reader 的历史；管理员也不能读取别人的聊天。
5. 停止 Qdrant 后提出新问题，看到 FAILED 和安全错误；恢复后手动重新提问。导入失败可在资料页重试。

M1 只支持 UTF-8 `.md/.txt`，256 KiB/文件、最多 100 片段。同名同内容返回原记录；同名不同内容拒绝，更新/删除入口已出现在当前 M2 快照中，接口参数见 docs/api.md，完整流程仍待验收。所有读者共享当前已发布资料，不设置每份文档 ACL。

## 真实模型配置

设置 `AI_MODE=real`，填写以下两组独立环境变量。无任何密钥写入代码。缺配置会拒绝启动，调用失败会显示失败，绝不自动转 mock。

| 组件 | 配置 | 提供方必须具有的能力 |
|---|---|---|
| 回答模型 | CHAT_BASE_URL、CHAT_API_KEY、CHAT_MODEL | OpenAI 兼容 `/v1/chat/completions`，支持 system/user 消息、非流式文本生成，遵循 JSON 陈述/引用协议，足够中文及上下文能力；支持本适配器的 temperature/max_tokens 参数 |
| Embedding | EMBEDDING_BASE_URL、EMBEDDING_API_KEY、EMBEDDING_MODEL、EMBEDDING_DIMENSIONS | OpenAI 兼容 `/v1/embeddings`，支持字符串列表，固定维度浮点向量，中文检索能力；查询和文档必须使用同一模型版本 |

BASE_URL 填根地址，不附加 `/v1`。目前只适配以上兼容协议，其他协议需要另写适配器；不能声称任意供应商可直接替换。维度配置是对实际输出的断言，本项目不发送 dimensions 缩维参数。CHAT_MODEL / EMBEDDING_MODEL 优先填写提供方固定快照名；若只有滚动别名，效果报告必须注明版本不可复现。

当前没有真实 API key、端点、回答模型快照或 Embedding 模型/维度的实测组合，真实效果未验证。真实模式会产生外部 API 调用，只有用户自行配置后才使用。最大上下文约为 Top-K × 600 码点＋元数据＋问题，M1 默认 4；提供方应留至少约 8k token 上下文余量，实际 token 计数需真实验证。

Embedding 模式/模型/端点/维度/切块参数组成 profile，并生成独立集合名，防止混用向量。切换后既有资料不会自动重新索引；M1 请使用独立空数据库部署来做真实试验，勿把空检索当模型无能力。重建及版本升级流程留在 M2。

## 验证与学习

```bash
./scripts/backend.sh test       # 无外部服务的确定性单元测试
./scripts/backend.sh package    # 单元测试和打包
./scripts/backend.sh verify     # 还需独立 knowledge_test、真实 Qdrant；不会连接开发库运行测试
cd frontend
npm ci
npm run build
cd ..
node scripts/smoke.mjs          # 已启动的 5175 HTTP 全流程；仅允许 mock，会导入三份模拟文件和本人记录
```

`verify` 使用 TEST_DB_*，启动 Flyway 前严格核验数据库名。集成测试使用独立 profile 对应的 Qdrant 集合，每条测试只清理自己创建的记录和点；不清用户库。完整模型效果评测在 M3，此处没有预填准确率。

[依赖兼容核对](docs/versions.md) · [M1 原理、排障与独立练习](docs/m1-learning.md) · [PLAN](PLAN.md) · [STATUS](STATUS.md)

M1 限制：同步导入；最多 4 个同时问答的背压（429），不是完整用户/IP 限流；网络超时、参数校验和失败可见已具备基础实现。账户管理、登录限流、完整更新删除一致性、崩溃恢复、20+20 分组评测均留后续阶段。无 SSE、多 Agent、MCP、微调、外部业务写入、OCR 或 Python 服务。没有商业用户、生产部署或模型训练经验声明。
