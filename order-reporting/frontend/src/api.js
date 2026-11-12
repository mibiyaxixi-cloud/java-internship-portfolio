export async function api(path, options = {}) {
  const headers = new Headers(options.headers)
  if (options.method && options.method !== 'GET') {
    const csrf = await api('/auth/csrf')
    headers.set(csrf.headerName, csrf.token)
  }
  const r = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin' })
  if (!r.ok) {
    const body = await r.json().catch(() => ({ message: `请求失败 (${r.status})` }))
    const e = new Error(body.message || '请求失败'); e.status = r.status; e.detail = body.detail; throw e
  }
  return r.status === 204 ? null : r.json()
}

export async function downloadCsv(path, filename) {
  const r=await fetch(`/api${path}`,{credentials:'same-origin'})
  if(!r.ok) {
    const body=await r.json().catch(()=>({message:`导出失败 (${r.status})`}))
    const e=new Error(body.message||'导出失败');e.status=r.status;throw e
  }
  if(!r.headers.get('content-type')?.includes('text/csv'))throw new Error('导出响应格式异常，未保存文件')
  const url=URL.createObjectURL(await r.blob())
  const a=document.createElement('a');a.href=url;a.download=filename;document.body.append(a);a.click();a.remove()
  setTimeout(()=>URL.revokeObjectURL(url),1000)
}
