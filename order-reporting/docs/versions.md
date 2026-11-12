# 固定版本与环境证据

依赖核对：2026-10-11～12；M3 复测：2026-10-13～14，macOS arm64。版本未升级。

| 工具/组件 | 固定版本或实测 |
|---|---|
| Java | Temurin 21.0.12.1，编译 release 21 |
| Maven / Wrapper | 3.9.11 / 3.3.4；全局 mvn 不存在，使用项目 Wrapper |
| MySQL Server | 8.4.11，本机已有二进制，隔离实例 13307 |
| Spring Boot | 3.5.16 |
| MyBatis Starter / MyBatis | 3.0.5 / 3.5.19 |
| Spring Security | Boot BOM 管理的 6.5.11 |
| Flyway | Boot BOM 管理的 11.7.2，含 MySQL 模块 |
| Connector/J | Boot BOM 管理的 9.7.0 |
| Commons CSV | 1.14.1 |
| Node / npm | 24.21.0 / 11.19.0 |
| Vue / Vite / plugin-vue | 3.5.43 / 8.3.4 / 6.0.9 |
| ECharts / zrender（M2） | 6.1.0 / 6.1.0 |

官方核对依据：

- [Spring Boot 3.5 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)：Java 17+、Maven 3.6.3+，本机版本满足。
- [MyBatis 官方兼容说明](https://github.com/mybatis/spring-boot-starter)与 [3.0.5 发布说明](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-3.0.5)：3.0.x 适配 Boot 3.2—3.5，3.0.5 基线 Boot 3.5。
- [Commons CSV 官方发行](https://commons.apache.org/proper/commons-csv/changes.html)：1.14.1 为正式版本，Java 8+；使用 RFC4180 解析器，不自行 split 逗号。
- [Vite 官方要求](https://vite.dev/guide/)：Node 20.19+ / 22.12+。npm 官方 registry 实际解析到所固定的 Vue/Vite/plugin 版本，插件 peer range 接受 Vite 8；完整传递依赖见 package-lock.json。

M2 新增 ECharts 6.1.0，确切版本和传递依赖写入 package-lock.json。使用 [官方按需导入](https://echarts.apache.org/handbook/en/basics/import/)（折线、柱状、SVG、网格、图例、提示、无障碍组件），按 [尺寸与销毁说明](https://echarts.apache.org/handbook/en/concepts/chart-size/) 监听容器 resize 并在卸载时 dispose。tooltip 使用 richText，不拼接业务文本 HTML。

最初尝试 6.0.0 下载因 ECONNRESET 中断；npm 审计提示旧版本公告后，核对 [Apache 6.1.0 官方发布说明](https://github.com/apache/echarts/releases/tag/6.1.0) 中的 Lines tooltip 修复，最终锁 6.1.0。与本项目折线/柱状配置相关的构建和浏览器验证记录在 M2 验收文档；没有借此宣称全面安全审计。

实际迁移时 Flyway 11.7.2 提示 MySQL 8.4 新于其已验证版本（日志给出 8.1）。未隐藏该提示；独立开发库/测试库迁移、再次 validate/migrate 和本项目事务验证通过，不宣称厂商全面认证。无需为消除警告而临时更换大版本。

本次沙箱曾阻止 Maven/npm 域名解析、MySQL 内存映射和本机 TCP。授权后重跑，实际结果以最终日志为准。最初触发器故障注入需要 SUPER 权限，已改用只作用于测试随机订单号的 CHECK 约束，没有扩大账号权限。

M3 环境补充：Apple M5、10 逻辑处理器、16 GiB RAM、macOS 27.0.1；MySQL InnoDB buffer pool 128 MiB。查询性能与复现边界见 performance.md。
