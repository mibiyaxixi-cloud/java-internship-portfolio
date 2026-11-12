# M1 HTTP 接口

所有路径 `/api`，使用同源 Session；写入需要先 GET `/csrf`，将返回 token 放入其 headerName 指定的头。登录成功及退出后重新获取 CSRF。无认证返回 401，无管理员角色或 CSRF 错误返回 403。

| 方法与路径 | 权限 | 含义 |
|---|---|---|
| GET /csrf | 公开 | 获取当前会话 CSRF |
| POST /login | CSRF | 表单 username / password |
| POST /logout | 登录＋CSRF | 退出并失效会话 |
| GET /me | 登录 | 用户、角色、明确的 mock/real 标识和模型标签 |
| GET /documents | 登录 | 读者只见有效版本；管理员可见失败状态、阶段与原因，最多100条 |
| POST /admin/documents | ADMIN＋CSRF | multipart file；同步导入，返回文档状态；业务导入失败返回 FAILED 对象，不冒充成功 |
| POST /admin/documents/{uuid}/retry | ADMIN＋CSRF | 重试同版本；成功版本幂等；正在导入返回409 |
| GET /documents/{uuid}/versions/{n} | 登录 | 当前有效完整原文；失效版本410 |
| GET /chunks/{uuid} | 登录 | 引用章节、原文、文件、文档/片段ID和版本；失效410 |
| POST /chats | 登录＋CSRF | JSON question（1—1000字符），返回 ANSWERED / INSUFFICIENT / FAILED 记录 |
| GET /chats | 登录 | 本人最近50条，动态标明引用是否有效 |
| GET /chats/{uuid} | 本人 | 不属于本人统一404，管理员也不例外 |

错误 JSON：code / message。输入400；文件超限413；并发问答槽满429；数据库不可用503。问答模型/检索故障可持久化时，返回200且 chat.status=FAILED，正文只包含安全错误说明，客户端必须检查状态。本次问题会保留，手动重试创建一条新记录。

未实现更新/删除 API、账号管理、分页、多轮上下文、SSE。当前文件全文仅 JSON 文本查看，没有任意路径或静态文件下载接口。
