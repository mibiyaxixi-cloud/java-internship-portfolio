// 本机验收使用的真实 HTTP 会话：不输出密码、Cookie 或 CSRF token。
import assert from 'node:assert/strict'
export function client(base) {
  const cookies = new Map()
  let lastHeaders
  async function send(path, options = {}, expected = 200) {
    const response = await fetch(`${base}${path}`, { ...options, signal: AbortSignal.timeout(15000),
      headers: { Accept: 'application/json', Cookie: [...cookies].map(([k,v]) => `${k}=${v}`).join('; '), ...options.headers } })
    lastHeaders = response.headers
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(';')[0], at = pair.indexOf('='), name = pair.slice(0, at), content = pair.slice(at + 1)
      if (content) cookies.set(name, content); else cookies.delete(name)
    }
    const data = response.status === 204 ? null : await response.json()
    assert.equal(response.status, expected, `${options.method || 'GET'} ${path}: ${JSON.stringify(data)}`)
    return data
  }
  async function write(path, method = 'POST', data, expected = 200, key = crypto.randomUUID(), form = false) {
    const csrf = await send('/api/auth/csrf')
    return send(path, { method, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': key,
      'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json' },
      body: data === undefined ? undefined : form ? new URLSearchParams(data) : JSON.stringify(data) }, expected)
  }
  const login = (username, password) => write('/api/auth/login', 'POST', { username, password }, 200, crypto.randomUUID(), true)
  return { send, write, login, get lastHeaders() { return lastHeaders } }
}
