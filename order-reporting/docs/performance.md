# M3：十万条模拟明细与真实查询分析

实测日期：2026-10-13～14，Asia/Shanghai。结论限于下面的本机单客户端、暖缓存、指定筛选；不是线上吞吐、导入压测或所有报表提速结论。

## 可复现的数据与环境

入口：项目根目录 `./scripts/benchmark.sh`。探针在测试源码 `BenchmarkMySqlProbe.java` 中，普通 verify 不运行它。先检查库名必须为 `order_reporting_test`，使用独立测试账号；不访问开发订单。没有新增软件依赖。

- Apple M5，10 个逻辑处理器，16 GiB 内存，macOS 27.0.1 arm64；Java 21.0.12.1、Maven 3.9.11、MySQL 8.4.11、InnoDB buffer pool 128 MiB、REPEATABLE READ、时区 +08:00。
- 10 个测试门店、20 个商品，50,000 笔订单，每单 2 行，合计 **100,000 条明细**；下单时间分布在 2085 年。
- PAID 44,750 单、CANCELLED 5,000 单、REFUNDED 250 单。每店 5,000 单，其中全额退款 25 单。退款发生在支付两天后。数量、单价、商品、日期按固定整数公式产生，非真实业务分布。
- 生成 20 份 CSV，每份 5,000 明细/2,500 单，均小于 1 MiB。实际通过 `ImportService.precheck → confirm` 分批导入，仍受字段校验、订单约束和每批 30 秒事务限制；没有绕过业务校验直接批量塞表，也没有一批塞入十万行。
- 每次输出独立 `.local/benchmark/时间/`，包含 CSV 和 result.json。两次运行的 20 个文件 SHA-256、大小、行数完全一致；自增 ID 和批次 UUID 可以不同。复现证据见 [文件哈希对比](evidence/m3-reproducibility.json)。

夹具使用保留前缀 BENCH_M3_。若已有同名门店/商品/账号即拒绝，不删除旧数据再重建。正常结束只按本次 actor、batch、store/product ID 清理自己写入的数据；两次运行后所有业务表行数都恢复到运行前。进程强杀可能留下夹具，脚本不会擅自清理；先保留证据核对归属。不要并行运行基准和其他测试。

## 选择真实查询

业务场景：门店账号查看全年全额退款订单的第一页（20 行）。这是现有 `OrderMapper.list`，配套 count 来自同一 Mapper；探针从 MyBatis MappedStatement 取得展开 SQL 和参数，避免手抄查询与实际实现分叉。示例门店 ID 是首轮夹具的 14：

```sql
SELECT o.id,o.store_id,s.name store_name,o.external_id,o.status,
       o.ordered_at,o.paid_at,o.refunded_at,o.total_cents,o.batch_id
FROM orders o JOIN stores s ON s.id=o.store_id
WHERE o.store_id=14
  AND o.ordered_at >= '2085-01-01 00:00:00'
  AND o.ordered_at <  '2086-01-01 00:00:00'
  AND o.status='REFUNDED'
ORDER BY o.ordered_at DESC,o.id DESC
LIMIT 20 OFFSET 0;
```

原有 `(store_id, ordered_at, id)` 已能限定门店/日期并反向扫描排序，但必须再过滤 status；退款稀少，找到 20 条结果要读很多候选记录。门店 JOIN 是主键上的 const，一单只连一个门店，不发生明细倍增。这里没有为了讲索引去增加一个虚构业务接口。

候选索引 `(store_id, status, ordered_at, id)` 把两个等值条件放前面，然后放日期范围与稳定排序。两列排序方向相同，MySQL 可反向扫描升序索引。原索引继续保留，服务没有 status 的查询；不能因为新索引更长就认为它替代了所有索引。原理依据：[MySQL 联合索引](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html)、[ORDER BY 优化](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)。

## 原始计划和实际时间

首轮是真正的 V2 无候选索引 → 测试库创建候选索引 → 再测。SQL 结果集和 Mapper 返回顺序保持一致。每个查询先 5 次预热，再 20 次 `System.nanoTime` 测量，包含 JDBC 执行与读取全部返回行；没有 HTTP、登录或前端耗时。SQL 前执行 ANALYZE TABLE 更新统计。另保存普通 EXPLAIN 与会实际执行查询的 EXPLAIN ANALYZE（[官方说明](https://dev.mysql.com/doc/refman/8.4/en/explain.html)）。

| 查询 | 原路径中位 ms | 候选索引中位 ms | 原路径 p95 ms | 候选 p95 ms |
|---|---:|---:|---:|---:|
| 分页列表 | 3.306250 | 0.189146 | 4.543625 | 0.245375 |
| 配套 count | 2.656438 | 0.090021 | 4.041209 | 0.129416 |

EXPLAIN ANALYZE 的首轮实际执行情况：

- 列表原路径：ix_store_ordered_id range + 反向扫描，读取 **4,173 行**后过滤出 20 行；Limit 节点约 `0.272..3.85 ms`。
- 列表候选：ix_store_status_ordered_id range + 反向扫描，读取 **20 行**即返回 20 行；Limit 节点约 `0.0255..0.056 ms`。
- count 原路径：uk_store_order 按门店读取 **5,000 行**，过滤为 25 行；候选使用覆盖索引范围读 **25 行**。
- 前后列表都没有 filesort；改进主要来自状态参与索引定位，不能写成“消除了原来的排序”。EXPLAIN 的估计 rows（原列表 9,338）也不是实际扫描 4,173，两者不能混用。

顺序测试可能受到缓存/JIT/运行顺序影响，因此又在同一索引已建环境中交替执行原路径（IGNORE INDEX 排除候选）和新路径，各 20 次：列表中位为 **2.912875 ms → 0.180688 ms**。完整样本保留，没有只挑最快一次。两组都支持这个场景的改善，但不外推到其他状态、无门店筛选、深分页或并发负载。

证据：[首轮完整 JSON：SQL、参数、EXPLAIN、ANALYZE、全部样本](evidence/m3-benchmark-before-after.json)、[V3 后复现 JSON](evidence/m3-benchmark-reproduction.json)、[硬件补充](evidence/m3-environment.txt)。第二轮在 V3 已有索引时，用 IGNORE INDEX 重建对照访问路径，不删除或改成不可见既有索引；`baselineMode` 明确标记，不能冒充再次真实删除索引的迁移前测试。

## 落地和结论边界

基于结果新增 Flyway `V3__store_status_order_paging.sql`，开发库/测试库均已验证迁移；没有改 V1/V2 历史迁移，也没有删除原索引或用户数据。测试探针首次临时创建的候选索引会在 finally 中移除；V3 以后只读已有索引，保留它。

新增索引会增加磁盘与写入维护成本；本轮没有测量其写入代价。数据中退款占 0.5%，范围很宽，对新索引有利，真实分布变化时优化器选择和收益都可能改变。未研究报表事件视图的十万行优化、深 OFFSET、冷缓存、多人并发、网络或生产容量，不宣称支持十万用户或生产级性能。
