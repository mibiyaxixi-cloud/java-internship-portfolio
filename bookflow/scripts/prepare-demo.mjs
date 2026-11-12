// 只追加缺失的演示密码，不覆盖已有 .env，也不改变数据库中已有账号。
import { randomBytes } from 'node:crypto'
import { readFileSync, appendFileSync, chmodSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
const path = fileURLToPath(new URL('../.env', import.meta.url))
const content = readFileSync(path, 'utf8')
const keys = ['DEMO_USER_PASSWORD', 'DEMO_SECOND_USER_PASSWORD', 'DEMO_ADMIN_PASSWORD']
const missing = keys.filter((key) => !new RegExp(`^${key}=`, 'm').test(content))
if (missing.length) {
  appendFileSync(path, '\n' + missing.map((key) => `${key}=${randomBytes(24).toString('base64url')}\n`).join(''))
  chmodSync(path, 0o600)
}
console.log(`已准备演示账号环境变量，新增 ${missing.length} 项。密码仅保存在 .env，请用编辑器查看。`)
