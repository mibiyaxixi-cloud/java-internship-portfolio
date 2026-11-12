// 独立 _test 库 + 临时 Java 子进程的真实崩溃恢复验收，不停止 8080 上的开发服务。
// 需要已构建的 JAR、mysql 客户端、TEST_DB_*、已初始化的 demo 账号。
import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { createWriteStream, writeFileSync } from 'node:fs'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { client } from './http-client.mjs'
process.loadEnvFile(fileURLToPath(new URL('../.env', import.meta.url)))
const root = fileURLToPath(new URL('../', import.meta.url))
const testUrl = process.env.TEST_DB_URL
assert.match(testUrl || '', /^jdbc:mysql:\/\/[^/]+\/[A-Za-z0-9_]+_test(?:[?].*)?$/, 'Requires a dedicated _test database')
const port = Number(process.env.RECOVERY_TEST_PORT || 18080), base = `http://127.0.0.1:${port}`
try { await fetch(`${base}/api/devices`, {signal:AbortSignal.timeout(1000)}); throw new Error(`Port ${port} is already in use`) }
catch (error) { if (!['TypeError','TimeoutError'].includes(error.name)) throw error }
const mysql = process.env.MYSQL_BIN ? join(process.env.MYSQL_BIN,'mysql') : 'mysql'
function sql(text, source = false) {
  const url = new URL((source ? process.env.DB_URL : testUrl).slice(5))
  return execFileSync(mysql, ['--protocol=TCP','-h',url.hostname,'-P',url.port || '3306','-u',source?process.env.DB_USERNAME:process.env.TEST_DB_USERNAME,
    '--batch','--skip-column-names',url.pathname.slice(1)], { input:text, encoding:'utf8', env:{...process.env,MYSQL_PWD:source?process.env.DB_PASSWORD:process.env.TEST_DB_PASSWORD} }).trim()
}
const quote = value => `'${String(value).replaceAll('\\','\\\\').replaceAll("'","''")}'`
const tag = `recovery-${Date.now().toString(36)}`, names = [`${tag}-a`,`${tag}-b`,`${tag}-admin`]
const password = process.env.DEMO_USER_PASSWORD
const hash = sql("SELECT password_hash FROM app_user WHERE username='student' AND is_demo=TRUE",true)
assert.match(hash,/^\$2[aby]\$/,'A demo account must exist in the application database')
const log = createWriteStream(join(root,'.local/recovery-server.log'),{flags:'a'})
let child, deviceId, slotId, userIds=[], firstResponse, requestKey, bEntry, adminEntry
async function start() {
  child = spawn(process.env.JAVA_HOME ? join(process.env.JAVA_HOME,'bin/java') : 'java',
    ['-jar',join(root,'backend/target/bookflow-backend-0.1.0.jar')],
    {cwd:root, env:{...process.env, DB_URL:testUrl, DB_USERNAME:process.env.TEST_DB_USERNAME, DB_PASSWORD:process.env.TEST_DB_PASSWORD,
      SPRING_PROFILES_ACTIVE:'', SERVER_ADDRESS:'127.0.0.1', SERVER_PORT:String(port), WAITLIST_OFFER_SECONDS:'10', WAITLIST_SCAN_DELAY_MS:'500', WAITLIST_SCHEDULING_ENABLED:'true'},stdio:['ignore','pipe','pipe']})
  child.stdout.pipe(log,{end:false}); child.stderr.pipe(log,{end:false})
  let spawnError; child.on('error',error=>{spawnError=error})
  for(let i=0;i<100;i++) {
    if(spawnError) throw spawnError
    if(child.exitCode!==null || child.signalCode!==null) throw new Error('Temporary backend exited; see .local/recovery-server.log')
    try { const response=await fetch(`${base}/api/devices`,{signal:AbortSignal.timeout(500)}); if(response.ok) return }
    catch {}
    await delay(250)
  }
  throw new Error('Temporary backend startup timed out')
}
async function stop(signal='SIGTERM') {
  if(child && child.exitCode===null && child.signalCode===null) { const ended=once(child,'exit'); child.kill(signal); await ended }
  child=undefined
}
async function loginAll() {
  const a=client(base),b=client(base),admin=client(base)
  await a.login(names[0],password); await b.login(names[1],password); await admin.login(names[2],password)
  return {a,b,admin}
}
try {
  await start() // 执行与主应用相同的 Flyway 迁移，但不启用 demo 种子。
  for(let i=0;i<names.length;i++) userIds.push(Number(sql(`INSERT INTO app_user(username,password_hash,display_name,role) VALUES(${quote(names[i])},${quote(hash)},'恢复测试用户',${quote(i===2?'ADMIN':'USER')}); SELECT LAST_INSERT_ID();`)))
  let {a,b,admin}=await loginAll()
  const device=await admin.write('/api/admin/devices','POST',{code:tag.toUpperCase(),name:'【恢复测试】临时设备',category:'测试',location:'独立测试库',description:'只用于脚本自身夹具',status:'ACTIVE'},201)
  deviceId=device.id
  const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(Date.now()+2*86400000))
  const slot=await admin.write(`/api/admin/devices/${device.id}/slots`,'POST',{startAt:`${date}T09:00:00`,endAt:`${date}T10:00:00`,status:'OPEN'},201)
  slotId=slot.id; requestKey=crypto.randomUUID()
  firstResponse=await a.write('/api/reservations','POST',{slotId},201,requestKey)
  bEntry=await b.write('/api/waitlist','POST',{slotId},201)
  adminEntry=await admin.write('/api/waitlist','POST',{slotId},201)
  await a.write(`/api/reservations/${firstResponse.reservation.id}/cancel`)
  const offered=(await b.send('/api/waitlist/mine')).find(x=>x.id===bEntry.id)
  assert.equal(offered.status,'OFFERED')
  const expires=new Date(`${offered.expiresAt}+08:00`).getTime()
  // 只杀本脚本创建的子进程。已提交的资格/期限/任务必须独立于进程存在。
  await stop('SIGKILL')
  assert.equal(sql(`SELECT status FROM waitlist_entry WHERE id=${bEntry.id}`),'OFFERED')
  console.log('PASS committed offer and task survive an actual backend process crash')
  await delay(Math.max(0,expires-Date.now()+1200))
  await start()
  await a.send('/api/auth/me',{},401)
  ;({a,b,admin}=await loginAll())
  let next
  for(let i=0;i<20;i++) {
    next=(await admin.send('/api/waitlist/mine')).find(x=>x.id===adminEntry.id)
    if(next.status==='OFFERED') break
    await delay(250)
  }
  assert.equal((await b.send('/api/waitlist/mine')).find(x=>x.id===bEntry.id).status,'EXPIRED')
  assert.equal(next.status,'OFFERED')
  assert.equal(sql(`SELECT COUNT(*) FROM reservation WHERE active_slot_id=${slotId}`),'1')
  console.log('PASS startup scan recovers missed deadline and advances exactly one waiter')
  const confirmed=await admin.write(`/api/waitlist/${adminEntry.id}/confirm`)
  assert.equal(confirmed.reservation.status,'CONFIRMED')
  assert.deepEqual(await a.write('/api/reservations','POST',{slotId},201,requestKey),firstResponse)
  assert.equal(a.lastHeaders.get('Idempotency-Replayed'),'true')
  assert.equal(sql(`SELECT COUNT(*) FROM reservation WHERE slot_id=${slotId}`),'3')
  console.log('PASS re-login, successful confirmation and persisted idempotency replay after restart')
  await delay(1100)
  assert.equal(sql(`SELECT status FROM waitlist_task WHERE slot_id=${slotId}`),'DONE')
  assert.equal(sql(`SELECT COUNT(*) FROM reservation WHERE active_slot_id=${slotId}`),'1')
  writeFileSync(join(root,'.local/recovery-result.json'),JSON.stringify({checkedAt:new Date().toISOString(),checks:4,crash:'SIGKILL owned child only',backendRestarts:1,oldOffer:'EXPIRED',nextWaiter:'CONFIRMED',activeCount:1,requestReplay:true},null,2)+'\n')
  console.log('PASS repeated scheduled scans preserve one confirmed reservation; recovery checks complete')
} finally {
  await stop()
  // 仅删除本脚本在独立 _test 库创建的夹具；保留其他测试/开发数据。
  if(userIds.length) sql(`DELETE FROM idempotent_request WHERE user_id IN (${userIds.join(',')});`)
  if(slotId) sql(`DELETE FROM waitlist_entry WHERE slot_id=${slotId}; DELETE FROM waitlist_task WHERE slot_id=${slotId}; DELETE FROM reservation WHERE slot_id=${slotId}; DELETE FROM equipment_slot WHERE id=${slotId};`)
  if(deviceId) sql(`DELETE FROM device WHERE id=${deviceId};`)
  if(userIds.length) sql(`DELETE FROM app_user WHERE id IN (${userIds.join(',')});`)
  log.end()
}
