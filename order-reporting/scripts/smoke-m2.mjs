import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
const root=new URL('../',import.meta.url)
const env=Object.fromEntries((await readFile(new URL('.env',root),'utf8')).split('\n').filter(s=>s&&!s.startsWith('#')).map(s=>{const i=s.indexOf('=');return [s.slice(0,i),s.slice(i+1).replace(/^['"]|['"]$/g,'')]}))
const base=process.env.API_BASE||`http://127.0.0.1:${env.SERVER_PORT||8081}/api`
function client(){
  const cookies=new Map()
  async function request(path,options={}){
    const r=await fetch(base+path,{...options,headers:{...options.headers,Cookie:[...cookies].map(([k,v])=>`${k}=${v}`).join('; ')},redirect:'manual'})
    for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0],i=pair.indexOf('=');cookies.set(pair.slice(0,i),pair.slice(i+1))}
    const text=await r.text();let body;try{body=JSON.parse(text)}catch{body=text}
    return {status:r.status,headers:r.headers,body}
  }
  async function login(username,password){const csrf=(await request('/auth/csrf')).body;assert.equal((await request('/auth/login',{method:'POST',headers:{[csrf.headerName]:csrf.token},body:new URLSearchParams({username,password})})).status,204)}
  return {request,login}
}
const admin=client(),shop=client(),anonymous=client(),range='from=2026-09-30&to=2026-10-01'
for(const path of [`/reports?${range}`,`/reports/lines?${range}`,`/reports/lines/export?${range}`,'/orders/export','/exports'])assert.equal((await anonymous.request(path)).status,401)
await admin.login('admin',env.DEMO_ADMIN_PASSWORD);await shop.login('store1',env.DEMO_STORE1_PASSWORD)
const stores=(await admin.request('/stores')).body,cs01=stores.find(s=>s.code==='CS01'),cs02=stores.find(s=>s.code==='CS02')
const report=(await admin.request(`/reports?${range}`)).body
// This smoke check expects the documented demo date range. Use MySQL IT for isolated fixtures.
assert.deepEqual(report.totals,{salesCents:20000,refundCents:10000,netCents:10000,paidOrders:3,refundedOrders:1})
assert.deepEqual(report.daily.map(d=>[d.day,d.salesCents,d.refundCents,d.netCents]),[['2026-09-30',14000,0,14000],['2026-10-01',6000,10000,-4000]])
assert.deepEqual(report.products.map(p=>[p.productCode,p.soldQuantity,p.refundedQuantity,p.salesCents,p.refundCents,p.netCents]),[['TEA',4,2,12000,6000,6000],['CAKE',2,1,8000,4000,4000]])
const own=(await shop.request(`/reports?${range}`)).body
assert.deepEqual(own.totals,{salesCents:16000,refundCents:10000,netCents:6000,paidOrders:2,refundedOrders:1});assert.equal(own.stores.length,1);assert.equal(own.stores[0].storeId,cs01.id)
const lines=(await admin.request(`/reports/lines?${range}&size=100`)).body
assert.equal(lines.total,6);assert.equal(lines.items.reduce((s,r)=>s+r.netCents,0),10000)
const refund=(await shop.request('/reports/lines?from=2026-10-01&to=2026-10-01&eventType=REFUND')).body
assert.equal(refund.total,2);assert.ok(refund.items.every(r=>r.externalId==='DEMO-A'&&r.eventAt==='2026-10-01T00:00:00'))
const original=(await shop.request(`/orders/${refund.items[0].orderId}`)).body
assert.equal(original.items.length,2);assert.equal(original.order.paidAt,'2026-09-30T23:59:59')
for(const path of ['/reports','/reports/lines','/reports/lines/export','/orders/export']){
  const rejected=await shop.request(`${path}?${range}&storeId=${cs02.id}`);assert.equal(rejected.status,403);assert.equal(rejected.headers.get('content-disposition'),null)
}
for(const query of ['from=2026-10-02&to=2026-10-01','from=2026-01-01&to=2027-01-02',`${range}&eventType=PAID`,`${range}&productId=-1`]){
  const rejected=await admin.request(`/reports/lines/export?${query}`);assert.equal(rejected.status,400);assert.equal(rejected.headers.get('content-disposition'),null)
}
const csv=await admin.request(`/reports/lines/export?${range}`)
assert.equal(csv.status,200);assert.match(csv.headers.get('content-type'),/text\/csv/);assert.match(csv.headers.get('content-disposition'),/attachment/)
// Demo fields contain no embedded newlines; general RFC4180 quoting is covered by Java CSV tests.
assert.equal(csv.body.trim().split('\r\n').length,7);assert.match(csv.body,/"-60\.00"/);assert.match(csv.body,/"-40\.00"/)
const ledger=await shop.request(`/orders/export?${range}`);assert.equal(ledger.status,200);assert.ok(!ledger.body.includes('DEMO-C'));assert.ok(ledger.body.includes('DEMO-D'))
const history=(await shop.request('/exports')).body;assert.ok(history.some(h=>h.kind==='ORDER_LEDGER'&&h.row_count===3))
const result={checkedAt:new Date().toISOString(),base,result:'PASS',totals:report.totals,daily:report.daily,eventLines:lines.total,checks:['session authentication','daily/store/product overview and exact cross-day totals','event drilldown to original order','store scope on reports and both exports','bad export filters return JSON without attachment','CSV response and own export history'],note:'Requires demo-orders.csv already imported and no extra orders in the demo date range; does not delete business data or measure performance.'}
await writeFile(new URL('.local/smoke-m2-result.json',root),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2))
