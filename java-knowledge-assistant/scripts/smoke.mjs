// HTTP acceptance of the mock pipeline, not model-quality evaluation. Does not delete user data.
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const root=fileURLToPath(new URL('../',import.meta.url));process.loadEnvFile(root+'.env')
const base=process.env.API_BASE||'http://127.0.0.1:5175/api'
const checks=[]
function client(){let cookie='',csrf=null;return {
 async req(path,options={},expected=200){const headers={...options.headers};if(cookie)headers.Cookie=cookie;if(options.method&&options.method!=='GET'&&csrf)headers[csrf.headerName]=csrf.token;const r=await fetch(base+path,{...options,headers,redirect:'manual'});const cookies=r.headers.getSetCookie();if(cookies.length)cookie=cookies.map(c=>c.split(';')[0]).join('; ');const text=await r.text();assert.equal(r.status,expected,`${path}: ${text.slice(0,300)}`);return text?JSON.parse(text):null},
 async login(name,password){csrf=await this.req('/csrf');await this.req('/login',{method:'POST',body:new URLSearchParams({username:name,password})});csrf=await this.req('/csrf');return this.req('/me')}
}}
const admin=client(),reader=client(),other=client()
await client().req('/documents',{},401);checks.push('anonymous denied')
const info=await admin.login('admin',process.env.ADMIN_PASSWORD);assert.equal(info.mode,'mock','This smoke script is only for explicit mock mode');
await reader.login('reader',process.env.READER_PASSWORD);await other.login('reader2',process.env.READER2_PASSWORD);checks.push('session login and CSRF rotation')
const imported=[]
for(const name of ['01-onboarding.md','02-equipment.md','03-expenses.txt']){
 const body=new FormData();body.append('file',new Blob([await fs.readFile(root+'samples/'+name)]),name)
 const d=await admin.req('/admin/documents',{method:'POST',body});if(d.status==='FAILED'){const retried=await admin.req(`/admin/documents/${d.id}/retry`,{method:'POST'});assert.equal(retried.status,'INDEXED',retried.failure);imported.push(retried)}else{assert.equal(d.status,'INDEXED',d.failure);imported.push(d)}
}
checks.push('three simulated documents indexed')
const forbidden=new FormData();forbidden.append('file',new Blob(['正文']),'forbidden.md');await reader.req('/admin/documents',{method:'POST',body:forbidden},403);checks.push('reader upload denied')
const invalid=new FormData();invalid.append('file',new Blob(['pdf']),'bad.pdf');await admin.req('/admin/documents',{method:'POST',body:invalid},400);checks.push('unsupported type rejected')
const large=new FormData();large.append('file',new Blob(['a'.repeat(256*1024+1)]),'large.md');await admin.req('/admin/documents',{method:'POST',body:large},413);checks.push('real HTTP upload limit')
const answer=await reader.req('/chats',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({question:'模拟网约车单次报销上限是多少？'})});assert.equal(answer.chat.status,'ANSWERED');assert.equal(answer.chat.mode,'mock');assert.ok(answer.chat.answer.includes('MOCK'));assert.ok(answer.citations.length)
for(const c of answer.citations){assert.ok(c.valid);const chunk=await reader.req('/chunks/'+c.id);assert.equal(chunk.content,c.content);const source=await reader.req(`/documents/${c.documentId}/versions/${c.version}`);assert.ok(source.content.includes(c.content))}
checks.push('mock answer, allowed citation and original source')
await other.req('/chats/'+answer.chat.id,{},404);await admin.req('/chats/'+answer.chat.id,{},404);assert.ok(!(await other.req('/chats')).some(c=>c.chat.id===answer.chat.id));checks.push('history isolated including admin')
const result={timestamp:new Date().toISOString(),base,mode:'mock',model:info.model,embedding:info.embedding,collection:info.collection,topK:info.topK,documents:imported.map(d=>({id:d.id,filename:d.filename,version:d.version,status:d.status,profile:d.profile})),checks,modelQualityEvaluation:null}
await fs.mkdir(root+'evidence',{recursive:true});await fs.writeFile(root+'evidence/m1-http-smoke.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({passed:checks.length,checks,mode:'mock',modelQualityEvaluation:null},null,2))
