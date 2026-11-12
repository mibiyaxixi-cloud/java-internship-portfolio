// M2 本地真实 HTTP 验收。创建带 CHECK 标识的新设备，保留两条取消历史，不修改已有设备。
// 需要 demo profile 和 .env 中的演示账号密码；凭据、Cookie、CSRF token 不打印。
import assert from 'node:assert/strict'
import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
process.loadEnvFile(fileURLToPath(new URL('../.env', import.meta.url)))
const base = process.env.API_BASE || 'http://127.0.0.1:8080'

function client() {
  const cookies = new Map()
  async function send(path, options = {}, expected = 200) {
    const response = await fetch(`${base}${path}`, {
      ...options,
      signal: AbortSignal.timeout(15000),
      headers: { Accept: 'application/json', Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; '), ...options.headers },
    })
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(';')[0]
      const at = pair.indexOf('=')
      const name = pair.slice(0, at), content = pair.slice(at + 1)
      if (!content) cookies.delete(name)
      else cookies.set(name, content)
    }
    const payload = response.status === 204 ? null : await response.json()
    assert.equal(response.status, expected, `${options.method || 'GET'} ${path}: ${JSON.stringify(payload)}`)
    return payload
  }
  async function write(path, method = 'POST', data, expected = 200, form = false) {
    const csrf = await send('/api/auth/csrf')
    return send(path, {
      method,
      headers: { 'Idempotency-Key': crypto.randomUUID(), [csrf.headerName]: csrf.token, 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json' },
      body: data === undefined ? undefined : form ? new URLSearchParams(data) : JSON.stringify(data),
    }, expected)
  }
  async function login(username, password, expected = 200) {
    assert.ok(password, 'Missing demo password environment variable')
    return write('/api/auth/login', 'POST', { username, password }, expected, true)
  }
  return { send, write, login }
}

const a = client(), b = client(), admin = client(), guest = client()
await guest.send('/api/reservations/mine', {}, 401)
await a.login('student', 'wrong-password', 401)
const userA = await a.login('student', process.env.DEMO_USER_PASSWORD)
const userB = await b.login('student2', process.env.DEMO_SECOND_USER_PASSWORD)
const adminUser = await admin.login('admin', process.env.DEMO_ADMIN_PASSWORD)
assert.equal(adminUser.role, 'ADMIN')
assert.equal(userA.demo, true)
assert.equal((await a.send('/api/auth/me')).username, 'student')
console.log('PASS real database login: wrong password rejected, user/admin sessions restored')
await a.send('/api/admin/reservations', {}, 403)
await a.send('/api/reservations', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"slotId":1}' }, 403)
console.log('PASS backend role checks and CSRF protection')

const tag = Date.now().toString(36).toUpperCase()
const deviceBody = { code: `CHECK-${tag}`, name: '【M2 验收】测试设备', category: '验收数据', location: '本地验收实验室', description: 'smoke-m2.mjs 创建的验收数据，非真实设备。保留取消历史。', status: 'ACTIVE' }
const device = await admin.write('/api/admin/devices', 'POST', deviceBody, 201)
const date = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(Date.now() + 2 * 86400000))
const slotBody = { startAt: `${date}T09:00:00`, endAt: `${date}T10:00:00`, status: 'OPEN' }
const slot = await admin.write(`/api/admin/devices/${device.id}/slots`, 'POST', slotBody, 201)
await admin.write(`/api/admin/devices/${device.id}/slots`, 'POST', { ...slotBody, startAt: `${date}T09:30:00` }, 409)
console.log('PASS administrator creates device/slot and overlapping slots are rejected')

await guest.write('/api/reservations', 'POST', { slotId: slot.id }, 401)
const first = await a.write('/api/reservations', 'POST', { slotId: slot.id }, 201)
assert.equal(first.reservation.userId, userA.id)
await a.write('/api/reservations', 'POST', { slotId: slot.id }, 409)
await b.write('/api/reservations', 'POST', { slotId: slot.id }, 409)
console.log('PASS reservation and duplicate occupancy rejection')
await b.write(`/api/reservations/${first.reservation.id}/cancel`, 'POST', undefined, 403)
await admin.write(`/api/reservations/${first.reservation.id}/cancel`, 'POST', undefined, 403)
assert.ok(!(await b.send('/api/reservations/mine')).some((item) => item.reservation.id === first.reservation.id))
console.log('PASS ownership checks: another user and admin cannot cancel the booking')
await admin.write(`/api/admin/devices/${device.id}`, 'PUT', { ...deviceBody, status: 'INACTIVE' }, 409)
await admin.write(`/api/admin/slots/${slot.id}`, 'PUT', { ...slotBody, status: 'CLOSED' }, 409)
await admin.write(`/api/admin/slots/${slot.id}`, 'PUT', { ...slotBody, endAt: `${date}T11:00:00` }, 409)
await admin.write(`/api/admin/slots/${slot.id}`, 'DELETE', undefined, 409)
console.log('PASS occupied device/slot cannot be shut down, rewritten or deleted')

const cancelled = await a.write(`/api/reservations/${first.reservation.id}/cancel`)
const cancelledAgain = await a.write(`/api/reservations/${first.reservation.id}/cancel`)
assert.equal(cancelled.reservation.status, 'CANCELLED')
assert.equal(cancelled.reservation.cancelledAt, cancelledAgain.reservation.cancelledAt)
const second = await b.write('/api/reservations', 'POST', { slotId: slot.id }, 201)
assert.notEqual(second.reservation.id, first.reservation.id)
assert.equal(second.reservation.userId, userB.id)
console.log('PASS cancellation releases the slot; a new booking has a new ID and history remains')
await a.write('/api/reservations', 'POST', { slotId: slot.id, userId: userB.id }, 400)
const all = await admin.send('/api/admin/reservations')
assert.ok(all.some((row) => row.reservation.id === first.reservation.id && row.reservation.status === 'CANCELLED'))
assert.ok(all.some((row) => row.reservation.id === second.reservation.id && row.reservation.status === 'CONFIRMED'))
await b.write(`/api/reservations/${second.reservation.id}/cancel`)
await admin.write(`/api/admin/slots/${slot.id}`, 'DELETE', undefined, 409)
await admin.write(`/api/admin/devices/${device.id}`, 'PUT', { ...deviceBody, status: 'INACTIVE' })
console.log('PASS admin sees both histories; cancelled history remains protected; device can then be disabled')

const disposable = await admin.write('/api/admin/devices', 'POST', { ...deviceBody, code: `TEMP-${tag}`, name: '【M2 验收】无历史设备' }, 201)
const disposableSlot = await admin.write(`/api/admin/devices/${disposable.id}/slots`, 'POST', slotBody, 201)
await admin.write(`/api/admin/slots/${disposableSlot.id}`, 'PUT', { ...slotBody, status: 'CLOSED' })
await admin.write(`/api/admin/devices/${disposable.id}`, 'DELETE', undefined, 409)
await admin.write(`/api/admin/slots/${disposableSlot.id}`, 'DELETE', undefined, 204)
await admin.write(`/api/admin/devices/${disposable.id}`, 'DELETE', undefined, 204)
console.log('PASS administrator edits/deletes unreferenced slots and devices')

await a.write('/api/auth/logout', 'POST', undefined, 204)
await a.send('/api/auth/me', {}, 401)
await a.login('student', process.env.DEMO_USER_PASSWORD)
assert.ok((await a.send('/api/reservations/mine')).some((row) => row.reservation.id === first.reservation.id && row.reservation.status === 'CANCELLED'))
console.log('PASS logout invalidates the session; logging in again still reads persisted history')
for (const session of [a, b, admin]) await session.write('/api/auth/logout', 'POST', undefined, 204)
mkdirSync(new URL('../.local/', import.meta.url), { recursive: true })
const result = { checkedAt: new Date().toISOString(), deviceId: device.id, slotId: slot.id, reservationIds: [first.reservation.id, second.reservation.id], finalStatus: 'two CANCELLED records; device INACTIVE', checks: 10 }
writeFileSync(new URL('../.local/m2-smoke-result.json', import.meta.url), JSON.stringify(result, null, 2) + '\n')
console.log(`M2 HTTP checks passed. Retained explicitly labeled check data: device #${device.id}, reservations #${first.reservation.id}/#${second.reservation.id}.`)
