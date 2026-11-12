// 用于 M1 本地演示库的真实 HTTP 检查，先启动 demo profile 和 MySQL。
import assert from 'node:assert/strict'

const base = process.env.API_BASE || 'http://127.0.0.1:8080'
async function request(path, expectedStatus) {
  const response = await fetch(`${base}${path}`, { signal: AbortSignal.timeout(12000) })
  assert.equal(response.status, expectedStatus, `${path} status`)
  assert.match(response.headers.get('content-type'), /application\/json/)
  return response.json()
}

if (process.argv.includes('--expect-db-down')) {
  for (const path of ['/api/devices', '/api/devices/1/slots']) {
    const data = await request(path, 503)
    assert.equal(data.code, 'DATABASE_UNAVAILABLE')
    assert.deepEqual(Object.keys(data).sort(), ['code', 'message'])
    console.log(`PASS ${path}: database failure returns 503 without SQL details`)
  }
} else {
  const devices = await request('/api/devices', 200)
  assert.equal(devices.filter((device) => device.demo).length, 3)
  const device = devices.find((item) => item.code === 'DEMO-MIC-01')
  assert.ok(device)
  console.log('PASS catalog: three explicitly marked demo devices')
  const schedule = await request(`/api/devices/${device.id}/slots`, 200)
  assert.equal(schedule.timeZone, 'Asia/Shanghai')
  assert.equal(schedule.slots.length, 3, 'refresh demo SQL if its initial seven-day window has expired')
  assert.ok(schedule.slots.every((slot) => slot.demo))
  assert.deepEqual(schedule.slots.map((slot) => slot.startAt.slice(11, 16)), ['09:00', '14:00', '16:00'])
  console.log('PASS schedule: database slots are ordered and timezone is explicit')
  const inactive = devices.find((item) => item.code === 'DEMO-PCR-01')
  assert.ok(inactive)
  assert.equal((await request(`/api/devices/${inactive.id}/slots`, 200)).slots.length, 0)
  console.log('PASS empty schedule: 200 and an empty array')
  assert.equal((await request(`/api/devices/${device.id}/slots?date=2035-02-30`, 400)).code, 'INVALID_ARGUMENT')
  console.log('PASS invalid date: 400')
  assert.equal((await request('/api/devices/999999/slots', 404)).code, 'NOT_FOUND')
  console.log('PASS missing device: 404')
  assert.equal((await request('/api/admin/reservations', 401)).code, 'UNAUTHENTICATED')
  console.log('PASS unavailable admin endpoint requires login: 401')
}
