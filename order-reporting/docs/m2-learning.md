# M2：SQL、数据流与独立练习

## 关键 SQL：先保留资金事件，再按日汇总

实际代码：`V2__report_events_and_exports.sql`、`ReportMapper.daily`。以下为便于阅读的等价示例，参数由服务端校验并绑定：

```sql
SELECT DATE(e.event_at) AS day,
       SUM(e.sales_cents) AS sales_cents,
       SUM(e.refund_cents) AS refund_cents,
       SUM(e.sales_cents - e.refund_cents) AS net_cents
FROM order_events e
WHERE e.event_at >= '2026-09-30 00:00:00'
  AND e.event_at <  '2026-10-02 00:00:00'
  AND e.store_id = :server_resolved_store_id
GROUP BY DATE(e.event_at)
ORDER BY day;
```

`order_events` 使用 `UNION ALL` 将 paid_at 和 refunded_at 展开成两种事件。DEMO-A 虽然当前是 REFUNDED，9/30 仍贡献 100 元销售，10/1 贡献 100 元退款。WHERE 筛事件时间，不筛当前 PAID 状态；右边界是次日零点，恰好排除范围外的午夜事件。门店账号的 store_id 来自认证身份，管理员选择全部时才省略门店条件。

这里不连接订单明细，所以两行订单不会把整单金额加两次。商品排行和下钻需要 JOIN order_items 时，累加的是每行 `line_cents`；退款事件取该行金额为退款、净额取负。切勿用 SUM(DISTINCT total_cents) 修复重复：两张金额相同的真实订单会被错误合并。

数据库返回有事件的日期，Service 再补零日期。总览将 totals、daily、stores、products 放在同一次只读 REPEATABLE READ 事务内；跨请求仍可能因新导入而改变。现阶段没有 EXPLAIN/性能提升证据，不能因 SQL 看起来合理就宣称已优化。

## 一次数据流：点退款，再下钻导出

1. 管理员选择演示日期，Vue 请求 `/reports`。服务端从 Session 找到账户，解析允许的门店范围，查询事件并按整数分返回。
2. 10/1 的退款是 100 元；点退款数据点会查询当天 `eventType=REFUND` 的事件明细。服务端再次校验范围和权限。
3. DEMO-A 返回两行：茶饮退款 60 元，糕点退款 40 元。点“原始订单”用 orderId 查询权限内订单，看到原支付日和全额退款日。
4. 点“导出此范围 CSV”，使用刚才已经应用的日期、门店、商品、事件筛选。后端先 count；超 10,000 行拒绝，否则在同一快照中每次取 500 行，生成完整有上限的 CSV，再记录生成日志、返回附件。
5. 图表 100 元、明细两行合计 100 元、CSV refund_yuan 合计 100.00 一致。改表单但未重新查询不会悄悄改变导出范围。文件生成成功记录不表示用户已经保存下载。

## 独立练习：增加“平均支付客单价”（20—30 分钟）

由你独立完成，本轮没有实现。

要求：在经营报表增加第四张指标卡，显示当前已应用范围的平均支付客单价，定义为销售额 ÷ 支付笔数。退款不从分子扣减，因为这里衡量支付事件；没有支付笔数时显示“—”。保留两位小数，不改动数据库和其他指标，不把明细行数当订单数。

建议先在纸上写预期，再定位 ReportPage.vue 的 totals 与现有金额格式化函数。不要从前 20 商品排行反推总额。

验收：

- 全部门店、两天：200 ÷ 3 = 66.67 元。
- 9/30：140 ÷ 2 = 70.00 元；10/1：60 ÷ 1 = 60.00 元，尽管该日净额为负。
- CS01、两天：160 ÷ 2 = 80.00 元。
- 空日期范围：显示“—”，没有 NaN/Infinity。
- 更改日期但不查询时，卡片仍对应已显示的报表；门店账号仍只见本店数据。

完成后说明：为何不能用净销售额除以支付笔数，也不能用事件明细行数作为分母。
