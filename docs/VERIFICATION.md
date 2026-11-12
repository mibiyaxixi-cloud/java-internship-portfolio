# 作品集整理与复核记录

日期：2026-11-12。对象：此作品集中的三个源码副本。

## 本次执行

在独立验证目录中使用原项目的依赖缓存离线构建，Java 21、Maven 3.9.11。未读取原项目 `.env`，未连接开发数据库，也未调用真实模型服务。

| 项目 | Java 单元测试 | Java package | Vue 生产构建 |
| --- | --- | --- | --- |
| BookFlow | 4 项通过，0 失败/错误/跳过 | 成功 | 成功 |
| 订单报表 | 4 项通过，0 失败/错误/跳过 | 成功 | 成功，有主包超过 500 kB 的提示 |
| 知页 | 23 项通过，0 失败/错误/跳过 | 成功 | 成功 |

合计 31 项单元测试。未将历史集成测试数量计入本次结果。

实际构建等价命令（Maven 可执行文件使用本机已下载的固定版本）：

```bash
# 各项目根目录；缓存目录用本地实际值替换
mvn -o -B -ntp -Dmaven.repo.local=/path/to/prepared-cache package
# 各项目 frontend 目录
npm run build
```

新环境请按各自 RUNNING.md 安装依赖，使用仓库的 Maven Wrapper。以上离线成功不等于首次联网安装、跨操作系统运行已验证。

## 本次修正

BookFlow 首次运行 4 项单元测试时，Mockito 默认 inline mock maker 需要动态附加 Java agent，当前受限环境不支持，导致测试初始化失败。现有测试只模拟 CatalogMapper 接口，因此增加 `backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`，选择 `mock-maker-subclass`。再次执行全部 4 项单元测试及打包通过；没有跳过测试或删除断言，没有修改生产代码。

## 历史证据与未复测范围

- BookFlow：原项目记录 2026-10-10 的 4 单元 + 44 真实 MySQL 集成测试、HTTP、浏览器和重启恢复结果，见 [原验收记录](../bookflow/docs/validation.md)。
- 订单报表：原项目记录 2026-10-14 的 4 单元 + 31 真实 MySQL 集成测试及 SQL 基准，见 [M3 验收](../order-reporting/docs/m3-validation.md)。报表浏览器交互待补。
- 知页：原 M1 历史结果不代表当前 M2 完成；当前状态及缺口见 [STATUS](../java-knowledge-assistant/STATUS.md)。

本次没有重跑真实 MySQL/Qdrant 集成、HTTP 流程、浏览器业务、Docker、真实模型或负载测试。

## 整理范围

- 汇总作品集首页、三个项目 README、核心代码入口、已有截图、运行说明和简历项目描述。
- 保留源码、迁移、测试、锁定依赖和历史验证材料；原项目目录未修改。
- 仅交付 `.env.example`；排除真实 `.env`、本地数据库、密钥文件、缓存、构建产物、旧 Git 元数据及本机编辑器路径。
- 知识库 README/STATUS 改为符合现有代码的 M2 开发快照；保留 M1 历史文档。
