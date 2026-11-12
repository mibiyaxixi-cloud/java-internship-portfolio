import { readFile, writeFile, mkdir } from 'node:fs/promises'
import assert from 'node:assert/strict'
const root=new URL('../',import.meta.url)
const env=Object.fromEntries((await readFile(new URL('.env',root),'utf8')).split('\n').filter(s=>s && !s.startsWith('#')).map(s=>{const i=s.indexOf('=');return [s.slice(0,i),s.slice(i+1).replace(/^['"]|['"]$/g,'')]}))
const base=process.env.API_BASE || `http://127.0.0.1:${env.SERVER_PORT||8081}/api`
function client(){
  const cookies=new Map()
  async function request(path,options={}){
    const r=await fetch(base+path,{...options,headers:{...options.headers,Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')},redirect:'manual'})
    for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0];const i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await r.text();let body;try{body=JSON.parse(text)}catch{body=text}return {status:r.status,body}
  }
  async function post(path,body){const token=(await request('/auth/csrf')).body;return request(path,{method:'POST',headers:{[token.headerName]:token.token},body})}
  async function login(name,password){assert.equal((await post('/auth/login',new URLSearchParams({username:name,password}))).status,204)}
  return {request,post,login}
}
function uploadBody(bytes){const data=new FormData();data.set('file',new Blob([bytes],{type:'text/csv'}),'demo-orders.csv');return data}
const admin=client(),shop=client(),anonymous=client()
assert.equal((await anonymous.request('/orders')).status,401)
await admin.login('admin',env.DEMO_ADMIN_PASSWORD)
await shop.login('store1',env.DEMO_STORE1_PASSWORD)
const bytes=await readFile(new URL('database/demo-orders.csv',root))
const checked=await admin.post('/imports/precheck',uploadBody(bytes));assert.equal(checked.status,200);assert.equal(checked.body.errors.length,0)
const batch=checked.body.id
const confirmed=await admin.post(`/imports/${batch}/confirm`);assert.equal(confirmed.status,200);assert.equal(confirmed.body.status,'SUCCEEDED')
const replay=await admin.post('/imports/precheck',uploadBody(bytes));assert.equal(replay.body.id,batch)
assert.equal((await admin.post(`/imports/${batch}/confirm`)).body.insertedCount,confirmed.body.insertedCount)
const stores=(await admin.request('/stores')).body;const second=stores.find(s=>s.code==='CS02')
assert.equal((await shop.request(`/orders?storeId=${second.id}`)).status,403)
assert.equal((await shop.post('/imports/precheck',uploadBody(bytes))).status,403)
assert.equal((await shop.post(`/imports/${batch}/confirm`)).status,404)
const all=(await admin.request('/orders?from=2026-09-30&to=2026-10-01&size=100')).body.items
const demo=all.filter(o=>o.externalId.startsWith('DEMO-'));assert.equal(demo.length,4)
const d=(await admin.request(`/orders/${demo.find(o=>o.externalId==='DEMO-A').id}`)).body
assert.equal(d.items.reduce((sum,i)=>sum+i.lineCents,0),10000);assert.equal(d.order.paidAt,'2026-09-30T23:59:59');assert.equal(d.order.refundedAt,'2026-10-01T00:00:00')
assert.equal((await shop.request(`/orders/${demo.find(o=>o.externalId==='DEMO-C').id}`)).status,404)
const own=(await shop.request('/orders?size=100')).body.items;assert.ok(own.every(o=>o.storeId!==second.id))
const oversized=await admin.post('/imports/precheck',uploadBody(new Uint8Array(1024*1024+1)));assert.equal(oversized.status,413)
await mkdir(new URL('.local/',root),{recursive:true})
const result={checkedAt:new Date().toISOString(),result:'PASS',demoOrders:4,demoItems:5,batchId:batch,checks:['session login and CSRF','precheck and confirm','same-file replay','cross-store list/import/detail denied','batch ownership','exact cents and cross-day events','real multipart 1 MiB limit']}
await writeFile(new URL('.local/smoke-result.json',root),JSON.stringify(result,null,2)+'\n')
console.log(JSON.stringify(result,null,2))
