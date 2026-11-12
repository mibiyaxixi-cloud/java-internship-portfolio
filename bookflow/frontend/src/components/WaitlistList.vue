<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getJson, intentWrite, writeJson, clearIntent } from '../api'
const props = defineProps({ user: Object, admin: Boolean })
const rows = ref([]), tasks = ref([]), loading = ref(true), error = ref(''), feedback = ref(''), busy = ref(null)
const now = ref(Date.now()), lastUpdated = ref(''), query = ref('')
const labels = { WAITING: '排队中', OFFERED: '等待确认', CONFIRMED: '已转为预约', EXPIRED: '已过期', CANCELLED: '已退出' }
const reasons = { CONFIRMATION_TIMEOUT: '确认超时', SLOT_UNAVAILABLE: '时段已开始或不可用', ACCOUNT_DISABLED: '账号已停用', USER_WITHDREW: '主动退出' }
const visible = computed(() => rows.value.filter(row => `${row.deviceName} ${row.deviceCode} ${row.username}`.toLowerCase().includes(query.value.trim().toLowerCase())))
let interval, ticking, request, stopped = false
function format(value) { return value ? value.replace('T', ' ').slice(0, 19) : '—' }
function expires(row) { return new Date(`${row.expiresAt}+08:00`).getTime() }
function confirmable(row) { return row.status === 'OFFERED' && expires(row) > now.value }
function remaining(row) {
  const seconds = Math.max(0, Math.ceil((expires(row) - now.value) / 1000))
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}
async function load(manual = false) {
  if (request || stopped) return
  request = new AbortController()
  if (manual) loading.value = true
  try {
    const result = await getJson(props.admin ? '/api/admin/waitlist' : '/api/waitlist/mine', request.signal)
    if (props.admin) tasks.value = await getJson('/api/admin/waitlist/tasks', request.signal)
    if (!stopped) { rows.value = result; error.value = ''; lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai' }) }
  } catch (reason) { if (reason.name !== 'AbortError') error.value = reason.message }
  finally { request = null; loading.value = false }
}
async function confirm(row) {
  if (busy.value) return
  busy.value = row.id; feedback.value = ''; error.value = ''
  try {
    const result = await intentWrite(props.user.id, `/api/waitlist/${row.id}/confirm`, row.id)
    clearIntent(props.user.id, '/api/waitlist', row.slotId)
    feedback.value = `已确认，预约记录 #${result.reservation.id}。请在我的预约查看当前状态。`
    await load()
  } catch (reason) { error.value = reason.message }
  finally { busy.value = null }
}
async function withdraw(row) {
  if (busy.value || !window.confirm('退出候补或放弃确认资格？会保留历史，并递补给下一位。')) return
  busy.value = row.id; feedback.value = ''; error.value = ''
  try {
    await writeJson(`/api/waitlist/${row.id}/withdraw`, 'POST')
    clearIntent(props.user.id, '/api/waitlist', row.slotId)
    feedback.value = '已退出候补，历史记录已保留。'
    await load()
  } catch (reason) { error.value = reason.message }
  finally { busy.value = null }
}
onMounted(() => { load(); interval = setInterval(() => { if (!busy.value) load() }, 5000); ticking = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { stopped = true; clearInterval(interval); clearInterval(ticking); request?.abort() })
</script>

<template>
  <section class="records-panel">
    <div class="section-heading"><h2>{{ admin ? '候补与调度任务' : '我的候补' }} <span>Waitlist</span></h2><button class="text-button" :disabled="loading || !!busy" @click="load(true)">刷新候补</button></div>
    <p class="muted">按成功入队顺序递补。获邀后名额暂时保留，请在显示期限前确认；超时自动递补。页面每 5 秒刷新，没有站外通知。北京时间。</p>
    <label class="search-box"><input v-model="query" type="search" placeholder="搜索设备或账号" aria-label="搜索候补"></label>
    <p v-if="feedback" class="feedback success" role="status">{{ feedback }}</p>
    <p v-if="error" class="feedback error" role="alert">{{ error }} <button class="text-button" @click="load(true)">刷新确认结果</button></p>
    <div v-if="loading" class="state-box" role="status">正在读取候补…</div>
    <div v-else-if="!rows.length && !error" class="state-box">暂无候补记录。设备时段满额后可加入候补。</div>
    <div v-else-if="rows.length && !visible.length" class="state-box">没有符合搜索条件的候补。</div>
    <div v-else class="reservation-list">
      <article v-for="row in visible" :key="row.id" class="reservation-card" :class="{ 'offer-card': row.status === 'OFFERED' }">
        <div><span class="equipment-code">候补 #{{ row.id }} · {{ row.deviceCode }}</span><h3>{{ row.deviceName }}</h3><p>{{ format(row.startAt) }} — {{ row.endAt.slice(11, 16) }}</p><p v-if="admin" class="muted">{{ row.displayName }}（{{ row.username }}）</p><p v-if="row.status === 'WAITING'">当前顺位 {{ row.position }}<small class="muted">（包含前方待确认的人）</small></p><p v-if="row.expiresAt" class="muted">确认期限 {{ format(row.expiresAt) }}</p><p v-if="row.status === 'OFFERED'" class="offer-deadline">{{ confirmable(row) ? '剩余 ' + remaining(row) : '确认期限已到，等待更新状态' }}</p><small class="muted">入队于 {{ format(row.queuedAt) }} <template v-if="row.reason"> · {{ reasons[row.reason] || row.reason }}</template></small></div>
        <div class="reservation-actions"><span class="slot-status" :class="row.status === 'OFFERED' ? 'available' : 'closed'">{{ labels[row.status] }}</span><button v-if="!admin && confirmable(row)" class="primary-button" :disabled="!!busy" @click="confirm(row)">确认预约</button><button v-if="!admin && (row.status === 'WAITING' || confirmable(row))" class="danger-button" :disabled="!!busy" @click="withdraw(row)">{{ row.status === 'OFFERED' ? '放弃资格' : '退出候补' }}</button><small v-if="row.reservationId" class="muted">关联预约 #{{ row.reservationId }}</small></div>
      </article>
    </div>
    <p v-if="lastUpdated" class="form-hint">最后更新 {{ lastUpdated }} · 倒计时仅作提示，后端校验最终期限。</p>
    <template v-if="admin"><h3>持久化调度状态</h3><p class="muted">任务在数据库中保留。PENDING 表示等待下一期限，DONE 表示当前没有待处理候补；失败后自动重试。</p><div v-if="!tasks.length && !loading" class="state-box">暂无调度任务。</div><article v-for="task in tasks" :key="task.slotId" class="management-row"><div><strong>时段 #{{ task.slotId }} · {{ task.status }}</strong><p class="muted">下次处理 {{ format(task.nextRunAt) }} · 连续失败 {{ task.failureCount }} 次</p><p v-if="task.lastError" class="feedback error">{{ task.lastError }}</p></div></article></template>
  </section>
</template>
