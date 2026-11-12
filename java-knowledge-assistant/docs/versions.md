# 依赖选择与官方兼容核对

核对时间：2026-10-14—15。没有使用里程碑、RC、SNAPSHOT 或 Maven 版本范围。不是宣称全部组件为最新发布版；选定可复现的稳定组合。Maven 的精确父 POM/BOM 锁定间接依赖，前端用精确 package.json + package-lock.json。

| 组件 | 锁定版本 / 选择依据 |
|---|---|
| Java | 编译目标 21；本机 Temurin 21.0.12.1+1 |
| Spring Boot | 3.5.16；官方系统要求 Java 17—25，Java 21 在范围内 |
| Spring AI | BOM 1.1.8；版本标签官方 Getting Started 明确支持 Boot 3.4.x / 3.5.x，其父 POM 构建基线 3.5.15；此项目用同线补丁 3.5.16 |
| Spring Security / Framework | 由 Boot 3.5.16 管理：6.5.11 / 6.2.19，未另行覆盖 |
| MyBatis starter | 3.0.5；官方 3.0 系列支持 Boot 3.2—3.5、Java 17+；保持已有项目的访问方案 |
| MySQL Connector/J | Boot 管理 9.7.0；官方 GA 发布说明支持 MySQL Server 8.0+ |
| MySQL Server | 本机 8.4.11；Compose 同为 8.4.11 |
| Flyway | Boot 管理 11.7.2，显式引入 flyway-mysql。**本机有 MySQL 8.4 比其已测试最高 8.1 更新的提示**；不隐去警告、不声称该精确组合获得官方认证。迁移和集成测试结果另见 STATUS |
| Qdrant | 官方稳定版 v1.15.5，直接使用 REST `/collections`、points upsert 和 query；没有引入可能错配的 Java SDK |
| Vue | 3.5.43 |
| Vite / plugin-vue | 8.3.4 / 6.0.9；官方 Node 要求 20.19+、22.12+，本机 24.21.0；npm 11.19.0 |
| Maven | Wrapper 3.9.11，脚本 3.3.4；Boot 要求 Maven 3.6.3+ |

官方来源：

- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring AI v1.1.8 Getting Started 原文](https://github.com/spring-projects/spring-ai/blob/v1.1.8/spring-ai-docs/src/main/antora/modules/ROOT/pages/getting-started.adoc)
- [Spring AI v1.1.8 构建 POM](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml)
- [MyBatis 官方兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)
- [MySQL Connector/J 9.7.0 GA 发布说明](https://dev.mysql.com/doc/relnotes/connector-j/en/news-9-7-0.html)
- [Flyway MySQL 支持说明](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)：当前滚动文档不能替代对 11.7.2 的运行警告。
- [Qdrant v1.15.5 官方发布](https://github.com/qdrant/qdrant/releases/tag/v1.15.5)、[REST 接口说明](https://qdrant.tech/documentation/interfaces/)
- [Vite 官方运行时要求](https://vite.dev/guide/)、[Vue 官方仓库发布](https://github.com/vuejs/core/releases)
- [Temurin 官方容器镜像](https://hub.docker.com/_/eclipse-temurin)

模型名称没有虚构默认真实版本：real 模式必须填写实际提供方模型/快照及维度。mock 版本固定为 mock-extract-v1、mock-bigram-hash-v1。M1 的编译、确定性规则和服务链路测试不能当作真实模型质量结论。

容器也使用完整版本标签：Temurin 21.0.12_8（JDK/JRE jammy）、Node 24.21.0-alpine、Nginx 1.28.0-alpine、MySQL 8.4.11、Qdrant v1.15.5。构建容器也通过本仓库 Maven Wrapper 固定 Maven 版本。[Temurin 官方固定标签](https://hub.docker.com/layers/library/eclipse-temurin/21.0.12_8-jre-jammy/images/sha256-a7cd1c8e991239117e3f79d0861e259830cbbd07c66935cfc5c46e0ccf2e1aed)。本机读取 Docker registry 清单摘要失败，未获得可用多架构 digest；标签级锁定不等于容器基础层永远不变。Compose 拉取/构建仍待有 Docker 的环境验证。
