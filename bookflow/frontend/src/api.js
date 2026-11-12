export class ApiError extends Error {
  constructor(message, status = 0, code = 'NETWORK_ERROR') {
    super(message)
    this.status = status
    this.code = code
  }
}

export async function requestJson(path, options = {}) {
  let response
  try {
    response = await fetch(path, {
      ...options,
      credentials: 'same-origin',
      headers: { Accept: 'application/json', ...options.headers },
    })
  } catch (error) {
    if (error.name === 'AbortError') throw error
    throw new ApiError('无法连接后端。若刚提交操作，请先刷新记录确认结果，再决定是否重试。')
  }
  if (response.status === 204) return null
  let data
  try { data = await response.json() }
  catch { throw new ApiError('后端未返回有效数据，请检查服务状态。', response.status) }
  if (!response.ok) {
    if (response.status === 401 && !['/api/auth/me', '/api/auth/login'].includes(path)) {
      window.dispatchEvent(new Event('bookflow:session-expired'))
    }
    throw new ApiError(data.message || `请求失败（${response.status}）`, response.status, data.code)
  }
  return data
}

export function getJson(path, signal) { return requestJson(path, { signal }) }

export async function writeJson(path, method, body, requestKey) {
  // 每次写入先获取当前 session 的 token，登录/退出后不会复用旧 token。
  // 不自动重发写操作；预约/候补由 intentWrite 提供可恢复的幂等键。
  const csrf = await getJson('/api/auth/csrf')
  return requestJson(path, {
    method,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token, ...(requestKey ? { 'Idempotency-Key': requestKey } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export async function login(username, password) {
  const csrf = await getJson('/api/auth/csrf')
  return requestJson('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', [csrf.headerName]: csrf.token },
    body: new URLSearchParams({ username, password }),
  })
}

// 只保存随机请求编号，不保存密码、Cookie 或响应。按登录用户/操作/资源隔离。
// 模糊失败保留键，刷新页面后再次提交仍是同一请求；成功/明确拒绝后才结束本次意图。
function intentScope(userId, path, target) { return `bookflow:intent:${userId}:${path}:${target}` }
export function clearIntent(userId, path, target) { sessionStorage.removeItem(intentScope(userId, path, target)) }
export async function intentWrite(userId, path, target, body) {
  const scope = intentScope(userId, path, target)
  let key = sessionStorage.getItem(scope)
  if (!key) { key = crypto.randomUUID(); sessionStorage.setItem(scope, key) }
  try {
    const result = await writeJson(path, 'POST', body, key)
    sessionStorage.removeItem(scope)
    return result
  } catch (error) {
    if (error.status >= 400 && error.status < 500) sessionStorage.removeItem(scope)
    throw error
  }
}
