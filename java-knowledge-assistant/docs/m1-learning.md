# M1 数据流与独立练习

## 文件如何变成可检索片段

1. Spring Security 检查管理员身份和 Session CSRF；Chunker 验证扩展名、256 KiB、UTF-8 和控制字符。原文保存在 MySQL，不按用户文件名写入磁盘。
2. 按 Markdown 标题分章节（TXT 也允许这种标题），每章最多 600 个 Unicode 码点一块，重叠 80 个，最多 100 块。没有标题的文本属于“正文”。片段 ID 由文档 UUID、版本、profile、序号确定；保留文件名、章节和原文。
3. 短事务写入 documents、document_versions、chunks。状态 PENDING → IMPORTING；Embedding 和 Qdrant 网络调用不占用长数据库事务。
4. AiGateway 将“章节＋原文”转向量。mock 是 256 维字符二元组哈希；real 使用 Spring AI 的 OpenAiEmbeddingModel。两者使用不同适配器、不同集合。
5. Qdrant 以片段 ID 为 point ID，保存文档 ID、版本、章节和 profile。重试覆盖相同 ID。全部 `wait=true` 写入返回 completed 后，再用短 MySQL 事务发布 INDEXED 版本和有效文档。

失败记录保留 stage 和 failure。已经写入 Qdrant 但没发布的片段，会被 MySQL 有效性检查排除。M1 没有替换或删除入口；其并发发布/清理流程留 M2。

## 一条问答的路径

```mermaid
flowchart LR
  A[Vue 提问] --> B[Security / 参数校验]
  B --> C[ChatService]
  C --> D[查询 Embedding]
  D --> E[Qdrant 候选 Top-K × 4]
  E --> F[MySQL 状态/版本/profile 核验]
  F --> G[最多 Top-K 条原文证据]
  G --> H[模型 JSON 陈述与引用]
  H --> I[引用白名单校验]
  I --> J[再次检查版本]
  J --> K[本人聊天与引用入库]
  K --> L[页面点击章节/文件原文]
```

空证据不调用回答模型，直接返回“资料不足”。有证据时模型也能拒答。引用白名单保证 ID 来自本次有效证据、每条陈述有引用；不能仅凭这个检查证明文字内容被原文支持，语义支持需要 M3 人工评测。每次问题独立检索，不自动发送之前的聊天。

历史查询带当前登录用户条件，客户端不能通过 username 字段切换归属；管理员也不能看其他人的聊天。来源页允许所有登录用户读取当前发布资料。

固定系统规则放 system message，question/evidence 经 JSON 编码放 user message，作为不可信资料。没有工具执行能力；文档中的“忽略规则”不能获得任何后端授权。提示约束不保证绝无内容诱导；引用白名单和有效版本过滤是服务端的确定性边界。

## 失败定位示例

Qdrant 停止后上传：页面 FAILED、stage=VECTOR_UPSERT、failure 提示向量服务不可用。记录文档 ID → 查后端日志 `import failed document=… attempt=… type=…` → 核对 Qdrant 日志、URL、密钥和端口 → 恢复 → 点“重试导入”。片段 ID/数量不变，全部成功后才发布。

进程中断可能留下 IMPORTING，10 分钟后可手动重试；不宣称自动恢复。回答故障保存 FAILED 记录、安全错误信息和耗时。数据库完全不可用时返回 503，不能保证保存失败；恢复后先刷新状态。M1 列表最多 100 文档/最近 50 聊天，后续再做分页。

## 20 分钟小修改（由你完成）

给引用详情增加“原文字符数”，按 Unicode 码点计数，让 `😀` 算 1 个字符。

- 5 分钟：从 `/api/chunks/{id}` 找到 Evidence 来源，决定数字由 Java 还是页面计算，并写一句理由。
- 10 分钟：新增返回字段和页面显示；不要改变片段 ID、切块或向量数据，不要重新导入文件。
- 5 分钟：写含“中文😀”的断言（预期 3），再打开引用对照原文。

验收：现有权限/引用测试通过；失效引用仍标失效；长度仅包含片段正文。此练习没有预先代做。
