# 版本与兼容性核对

核对日期：2026-10-09（Asia/Shanghai）。

- Spring Boot 3.5.16 官方要求 Java 17+、Maven 3.6.3+，本项目 Java 21 与 Maven 3.9.11 满足条件：[官方系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。
- MyBatis Starter 的 3.0.x 分支支持 Spring Boot 3.2～3.5，3.0.5 发布记录基于 Boot 3.5：[官方仓库兼容表](https://github.com/mybatis/spring-boot-starter)、[3.0.5 发布说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5)。
- Vue 3.5.43、Vite 8.3.4、@vitejs/plugin-vue 6.0.9 通过 npm 官方 registry 核对；插件 peerDependencies 接受 Vite 8 和 Vue 3。Node 24.21.0 满足 Vite 的 Node 要求：[Vite 文档](https://vite.dev/guide/)。
- Maven Wrapper 脚本来自 Maven Central 的 3.3.4 官方 only-script 发行包；固定 Maven 下载地址为 3.9.11：[Wrapper 说明](https://maven.apache.org/tools/wrapper/)。
- Flyway 的正式迁移与显式演示 repeatable 分开：[Repeatable 迁移语义](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/repeatable-migrations)。
- 有效占用约束利用 MySQL 唯一索引允许多个 NULL 的语义：[MySQL 8.4 CREATE INDEX](https://dev.mysql.com/doc/refman/8.4/en/create-index.html)。

不以“最新”作为版本策略；选择互相兼容且本机能运行的固定版本。后续升级应作为独立变更重跑测试。实际构建解析到的核心依赖如下（由固定 BOM / Starter 管理）：

| 组件 | 解析版本 |
| --- | --- |
| Spring Security | 6.5.11 |
| MyBatis / MyBatis Spring | 3.5.19 / 3.0.5 |
| Flyway Core / MySQL 模块 | 11.7.2 |
| MySQL Connector/J | 9.7.0 |
| JUnit Jupiter | 5.12.2 |
| Surefire / Failsafe | 3.5.6 |


## 已知版本提示

实测 Flyway 11.7.2 在 MySQL 8.4.11 上输出“数据库新于其已验证版本”的建议升级日志。没有关闭或过滤该警告。本项目的首次建表、校验、重复 migrate 和 demo SQL 都已在 8.4.11 上实际通过；这些结果只覆盖本项目用到的迁移操作，不等于该组合获得厂商全面认证。暂保留 Boot BOM 的固定版本，避免为消除日志引入未经验证的大版本覆盖；升级 Flyway/数据库应单独重跑迁移回归。参考 [Flyway 官方 MySQL 文档](https://documentation.red-gate.com/fd/mysql-277579322.html)。

JUnit 单元测试中的 Mockito 在 Java 21 输出动态代理加载提示，当前不影响测试结果；未来升级 JDK 时再按 Mockito 官方说明配置显式 agent。

## M2 核对

M2 沿用全部固定版本，没有增加前端依赖或覆盖 Spring Security 版本。Spring Security 6.5.11 的 Session/XOR CSRF 默认行为和登录后 token 更新方式参考 [6.5 官方 CSRF 文档](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)。MyBatis 锁查询明确刷新一级缓存，参考 [官方 Java API：Local Cache / Options](https://mybatis.org/mybatis-3/java-api.html)。最终兼容性以本项目 Java 21 构建、真实 MySQL 测试及 HTTP/网页验证为边界，不宣称生产认证。
