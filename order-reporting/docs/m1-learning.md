# M1：关键 SQL、数据流与独立练习

## SQL：门店范围下的订单分页

门店账号查询一天的订单，最终形态如下（参数不是字符串拼接）：

```sql
SELECT o.id, o.external_id, s.name, o.total_cents, o.status
FROM orders o
JOIN stores s ON s.id = o.store_id
WHERE o.store_id = ?
  AND o.ordered_at >= ?
  AND o.ordered_at < ?
ORDER BY o.ordered_at DESC, o.id DESC
LIMIT ? OFFSET ?;
```

对应 `OrderMapper.FILTER` / `list` / `count`。`store_id` 来自 `Auth.scope`：门店账号的传入 storeId 只能为空或等于本人门店，空值会补为本人门店；管理员可选择门店或不限制。前端隐藏选项不能代替这一步。

JOIN 这里只连接每笔订单恰有一条的 stores，因此不会把订单重复成多行。明细按授权后的订单 ID 单独查询。如果以后连接 order_items 再 `SUM(o.total_cents)`，100 元的两行订单会错误累计成 200 元；`SUM(DISTINCT total_cents)` 也不对，因为不同订单可能同价。应在订单层汇总整单金额，商品层汇总明细金额。

日期使用左闭右开：9/30 是 `[9/30 00:00:00,10/1 00:00:00)`，无需构造“当天最后一毫秒”，也没有在索引列上包 `DATE()`。同秒订单再按 ID 倒序使分页排序稳定。列表与 count 使用同一筛选片段及同一个只读 REPEATABLE_READ 事务。索引只是初始设计，实际优化验证留待 M3。

## 一次 CSV 请求怎样到达数据库

1. Vue 请求 CSRF token，把文件放进 FormData；Session Cookie 指明登录账号。
2. Security 检查登录/CSRF，Auth 从数据库读账号角色和门店。
3. ImportService 校验文件大小/哈希；CsvValidator 用 Commons CSV 解析，引号内逗号不会拆列。
4. BigDecimal 将元精确转为分；同单各行校验一致，检查行号唯一、外键和整单合计，生成与行序无关的内容哈希。
5. BatchTransactions 提交预检查批次，返回 READY/INVALID。此时订单表没变。
6. 点击确认后，ImportWriter 在事务里锁批次、重读原文件并重新检查。新订单与全部明细一起提交，成功记录与业务同事务。
7. 写入失败则整批回滚，ImportService 调用另一个 bean 的 REQUIRES_NEW 保存 FAILED 和错误。后端再把结果交给 Vue。

看代码建议顺序：Model → CsvValidator → ImportService → ImportWriter → BatchTransactions → OrderService → OrderMapper → WorkflowMySqlIT。

## 你的独立练习（20—30 分钟，不附实现）

给订单台账增加“订单号精确查询” `orderNo`，允许与门店、日期、状态筛选组合；本次交付未代做。

- 前 5 分钟：自己写下匹配规则：空字符串如何处理、是否允许逗号/双引号、最大长度是多少。
- 接下来 10—15 分钟：从 Vue 表单一路传到 Controller、Service、Mapper；列表与 count 同时生效，使用 MyBatis `#{...}` 绑定。
- 最后 5—10 分钟：创建两个门店同名订单，用门店账号查询不能看到另一店；检查引号/逗号订单号、空结果、超过长度、翻页总数。现有集成测试也必须仍通过。

完成后用自己的话回答：为什么只在 SQL 加 `external_id = ?` 不能解决权限？为什么 list 改了但 count 没改会导致分页错误？不要提前实现统计、导出或缓存。
