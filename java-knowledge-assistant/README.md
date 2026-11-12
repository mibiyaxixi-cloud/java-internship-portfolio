# 知页 · Java 知识库问答助手

把共享 Markdown/TXT 资料切块后写入 MySQL 与 Qdrant，检索相关片段，再生成带可核对原文引用的回答。

**Java 21 · Spring Boot · Spring AI · MyBatis · MySQL · Qdrant · Spring Security · Vue 3**

> 当前是 **M2 开发快照**。M1 有历史验收记录，M2 的版本管理、删除清理、分页和限流代码已存在，但还没有完成阶段交付验收。默认运行 MOCK；未验证真实模型效果，不展示准确率。

[运行说明](RUNNING.md) · [当前状态](STATUS.md) · [接口说明](docs/api.md) · [作品集首页](../README.md)

## 已有实现

- 管理员导入 UTF-8 Markdown/TXT，章节切块、确定性片段 ID、导入状态与失败重试。
- Qdrant 向量检索后，使用 MySQL 再校验可见性、有效版本和索引状态。
- 服务端检查回答引用是否属于本次检索证据；引用可追溯到文件、章节、版本和原文。
- 所有读者共享已发布资料，各用户聊天历史隔离；管理员不能读取他人聊天。
- M2 源码包含文档替换、删除与向量清理、分页、请求背压和限流；作为待完整验收实现保留。
- 12 份明确标注模拟的示例资料。MOCK 与真实模型适配器分离；没有真实密钥配置时不宣称真实模型调用成功。

## 重点代码

| 关注点 | 代码入口 |
| --- | --- |
| 文档与版本流程 | [DocumentService](src/main/java/com/example/knowledge/DocumentService.java) |
| 问答与用户记录 | [ChatService](src/main/java/com/example/knowledge/ChatService.java) |
| 引用校验 | [CitationValidator](src/main/java/com/example/knowledge/CitationValidator.java) |
| 模型接口适配 | [RealAiGateway](src/main/java/com/example/knowledge/RealAiGateway.java) |
| 工作流集成用例 | [WorkflowIT](src/test/java/com/example/knowledge/WorkflowIT.java) |

## 展示边界

M1 历史测试结果不能直接代表当前 M2 快照已完成全流程验收。本次编译、单元测试和前端构建结果在 [VERIFICATION](../docs/VERIFICATION.md) 单独记录。Docker Compose 启动、真实模型效果与完整浏览器操作仍未验证。

建议在简历中将本项目列为“学习中”，优先展示已经完整交付的 BookFlow 和订单报表。
