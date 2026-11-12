# 接口（M1 + M2）

根路径 `/api`，同源 Cookie Session。身份和门店从服务端账号获得，客户端无需也不能指定 actorId。日期 ISO `YYYY-MM-DD`，时间上海本地 `YYYY-MM-DDTHH:mm:ss`，响应金额均为 `*Cents` 整数分。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/auth/csrf` | 匿名可用，返回 `{headerName,token}` |
| POST | `/auth/login` | 表单 username/password + CSRF；成功 204，错误 401 |
| GET | `/auth/me` | 当前账号 id/username/role/storeId；无密码哈希 |
| POST | `/auth/logout` | CSRF，成功 204 |
| GET | `/stores` | 管理员全部，门店账号仅本店 |
| GET | `/products` | 共享商品编码和名称 |
| GET | `/imports/template` | 下载固定空 CSV 表头 |
| POST | `/imports/precheck` | multipart 字段 file；返回 BatchView；无订单写入 |
| POST | `/imports/{id}/confirm` | 确认保存的原文件；重新校验并整批提交 |
| GET | `/imports` | 当前账号最近 30 批，`{items:[BatchView]}` |
| GET | `/imports/{id}` | 当前账号批次结果 |
| GET | `/imports/{id}/audit` | 当前账号批次操作日志 |
| GET | `/orders` | 筛选及服务端分页 |
| GET | `/orders/{id}` | `{order,items}`，查询先限定门店再取明细 |

登录成功会更新 Session/CSRF；每次写入前重取 token。缺少 CSRF 的写请求返回 403。未登录读返回 401；登录后超范围显式门店过滤返回 403；订单/批次详情无权访问返回 404，避免泄漏对象是否存在。

订单筛选：可选 `storeId`、`from`、`to`、`status`，默认 `page=1&size=20`，size 1—100、page 1—10000。from/to 按下单日，包含两端自然日，内部用 `[from 00:00:00, to+1日 00:00:00)`；范围年份 2000—2100。状态仅 PAID/CANCELLED/REFUNDED。返回 `{items,total,page,size}`。排序为 `ordered_at DESC,id DESC`。不存在的门店过滤可返回空结果；非法日期/分页/状态返回 400。订单台账导出使用完全相同的筛选，但不接收 page/size，见下文。

BatchView 示例（完整原文件不返回）：

```json
{
  "id":"uuid", "status":"READY", "rowCount":5, "orderCount":4,
  "insertedCount":0, "skippedCount":0, "errors":[],
  "createdAt":"2026-10-11T23:55:15.970115"
}
```

预检查普通格式/业务错误：200 + `status=INVALID`，`errors=[{"line":3,"reason":"…"}]`；不等于导入成功。越权文件：403，批次作为 `detail` 返回便于查看行原因，整批无订单写入。超过 1 MiB：413，不创建批次。确认校验失败：422（越权则 403）并记录 FAILED。数据库争用返回 409 或 503；重试确认重新校验，失败不产生本批业务半成品。原文件没有变化时再次预检查返回原批次状态，可在页面“重新校验并重试”；修改后的文件产生新批次。

统一错误体：`{"code":"...","message":"...","detail":...}`，安全过滤器响应可省略 detail。数据库断连 503 不伪装成空订单。详情行号 0 表示批次/事务错误，不是 CSV 数据行。

端到端调用样例见 `scripts/smoke.mjs`：使用 Cookie、CSRF、FormData，秘密只从本机 .env 读取，不打印。

## M2 报表与导出

| 方法 | 路径 | 参数与返回 |
|---|---|---|
| GET | `/reports` | 必填 from/to，可选 storeId；返回 totals、daily、stores、products |
| GET | `/reports/lines` | 必填 from/to，可选 storeId、productId、eventType、page、size；返回分页事件明细 |
| GET | `/reports/lines/export` | 与事件明细相同的业务筛选，不接收分页；CSV 附件 |
| GET | `/orders/export` | 与订单列表相同的业务筛选，不接收分页；CSV 附件 |
| GET | `/exports` | 当前账号最近 30 次文件生成记录；数组，每项有 kind、filters_json、row_count、created_at |

报表 from/to 按上海**事件日期**，两端包含，必填、2000—2100 年、最多 366 个自然日。SQL 半开区间 `[from 00:00, to+1日 00:00)`。eventType 仅 PAYMENT/REFUND，缺省为全部；productId 必须存在。分页范围与订单列表一致。未登录 401，门店账号显式指定其他门店 403；省略 storeId 仍强制本店。总览不接收 status/productId/eventType：退款快照仍保留支付历史；商品和事件筛选用于明细下钻。错误日期、超长范围、非法事件/商品返回 400。

示例：`GET /api/reports?from=2026-09-30&to=2026-10-01`，导入原演示文件且没有其他同期订单时：

```json
{"from":"2026-09-30","to":"2026-10-01","timeZone":"Asia/Shanghai","dateBasis":"PAYMENT_AND_REFUND_EVENTS","totals":{"salesCents":20000,"refundCents":10000,"netCents":10000,"paidOrders":3,"refundedOrders":1},"productLimit":20}
```

实际响应还含 daily（缺失日补零）、stores（权限内零事件门店也展示）、products（净销售额前 20 名，同额按 ID 升序）。前 20 名不保证覆盖全部商品，不能用该排行当全量总计。

事件明细按 eventAt 倒序、orderId 倒序、eventType 升序、lineNo 升序；一行是一个商品明细的一个支付或退款事件。一笔两行全额退款订单，在跨越两个事件日期的范围内产生四行。salesCents/refundCents 为非负值，netCents 可负；quantity 为对应事件件数。status 是当前快照，仅供查看，不参与事件历史过滤。

两类 CSV 均为 UTF-8 BOM + RFC4180，所有字段加双引号。事件 CSV 金额为两位小数元（sales_yuan/refund_yuan/net_yuan）；台账 original_total_yuan 为原金额，包含已取消/已退款订单，**不是净销售额**。接口先 count，最多 10,000 数据行，每页取 500 行，同一 REPEATABLE READ 事务；文件最多 16 MiB、事务 30 秒。超量 422 EXPORT_LIMIT，超大小 422 EXPORT_TOO_LARGE，数据库异常 503；出错返回 JSON，无附件头，不发送半份 CSV。空结果可导出只有表头的文件。

订单号、门店/商品代码与名称等用户文本若以公式字符、全角对应字符、空白/控制/格式字符开头，会前缀单引号；合法数值由后端生成，负净额保持数值。转义不改变数据库原文。CSV 供查看和核对，不是导入模板；防护不承诺经所有表格软件再次保存/打开后仍保持，参见 [OWASP CSV Injection](https://owasp.org/www-community/attacks/CSV_Injection)。

导出记录表示服务端完整生成文件成功，不代表客户端已保存。生成和审计失败均不返回成功附件；审计在独立事务保存。统计查询、下钻、导出每次请求内部一致，不承诺相隔数秒的多个请求共享快照：期间有新导入时，应重新查询后核对。

复现：`API_BASE=http://127.0.0.1:5174/api node scripts/smoke-m2.mjs`。只读既有演示订单，创建正常导出记录；不清理业务数据。固定日期如有新增业务订单会使样例总额断言失败，隔离对账使用真实 MySQL 集成测试。
