<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getJson, writeJson, clearIntent } from '../api'
const props = defineProps({ admin: Boolean, user: Object })
const rows = ref([])
const loading = ref(true)
const error = ref('')
const feedback = ref('')
const busyId = ref(null)
const status = ref('ALL')
const query = ref('')
const visible = computed(() => rows.value.filter(({ reservation: item }) =>
  (status.value === 'ALL' || item.status === status.value) &&
  `${item.deviceName} ${item.deviceCode} ${item.username} ${item.displayName}`.toLowerCase().includes(query.value.trim().toLowerCase())))
let request
async function load() {
  request?.abort()
  const current = new AbortController()
  request = current
  loading.value = true
  error.value = ''
  try { rows.value = await getJson(props.admin ? '/api/admin/reservations' : '/api/reservations/mine', current.signal) }
  catch (reason) { if (reason.name !== 'AbortError') { error.value = reason.message; rows.value = [] } }
  finally { if (request === current) loading.value = false }
}
async function cancel(item) {
  if (busyId.value || !window.confirm(`确认取消「${item.deviceName}」${format(item.startAt)} 的预约？历史记录会保留。`)) return
  busyId.value = item.id
  feedback.value = ''
  try {
    await writeJson(`/api/reservations/${item.id}/cancel`, 'POST')
    clearIntent(props.user?.id, '/api/reservations', item.slotId)
    feedback.value = '预约已取消，历史记录已保留；如有候补，已按顺序递补。'
    await load()
  } catch (reason) { error.value = reason.message }
  finally { busyId.value = null }
}
const labels = { CONFIRMED: '已预约', CANCELLED: '已取消', HELD: '待确认资格', EXPIRED: '资格已过期' }
function format(value) { return value ? value.replace('T', ' ').slice(0, 16) : '—' }
onMounted(load)
onUnmounted(() => request?.abort())
</script>

<template>
  <section class="records-panel">
    <div class="section-heading"><h2>{{ admin ? '全部预约记录' : '我的预约' }} <span>Reservations</span></h2><button class="text-button" :disabled="loading || !!busyId" @click="load">刷新记录</button></div>
    <p class="muted">{{ admin ? '查看全部用户的有效预约与取消历史。管理员不能代用户取消。' : '时段开始前可取消；取消后可以重新预约，原记录始终保留。' }}</p>
    <div class="filter-row"><label>预约状态<select v-model="status"><option value="ALL">全部状态</option><option value="CONFIRMED">已预约</option><option value="CANCELLED">已取消</option><option value="HELD">待确认资格</option><option value="EXPIRED">资格已过期</option></select></label><label>搜索<input v-model="query" :placeholder="admin ? '设备或预约人' : '设备名称或编号'" type="search"></label></div>
    <p v-if="feedback" class="feedback success" role="status">{{ feedback }}</p>
    <div v-if="error" class="feedback error" role="alert">{{ error }} <button class="text-button" @click="load">刷新确认结果</button></div>
    <div v-if="loading" class="state-box" role="status"><span class="spinner"></span>正在读取预约…</div>
    <div v-else-if="!rows.length && !error" class="state-box"><strong>暂无预约记录</strong><p>{{ admin ? '用户提交预约后会显示在这里。' : '去设备目录选择一个开放时段，开始第一次预约。' }}</p></div>
    <div v-else-if="rows.length && !visible.length" class="state-box">没有符合筛选条件的记录。</div>
    <div v-else class="reservation-list">
      <article v-for="{ reservation: item, cancellable } in visible" :key="item.id" class="reservation-card">
        <div><span class="equipment-code">#{{ item.id }} · {{ item.deviceCode }}</span><h3>{{ item.deviceName }}</h3><p>{{ format(item.startAt) }} — {{ item.endAt.slice(11, 16) }}</p><p class="muted">{{ item.location }}<template v-if="admin"> · {{ item.displayName }}（{{ item.username }}）</template></p><small class="muted">创建于 {{ format(item.createdAt) }}<template v-if="item.cancelledAt"> · 取消于 {{ format(item.cancelledAt) }}</template></small></div>
        <div class="reservation-actions"><span class="slot-status" :class="item.status === 'CANCELLED' ? 'closed' : 'available'">{{ labels[item.status] || item.status }}</span><small v-if="item.status === 'HELD'" class="muted">请到我的候补确认<br>期限 {{ format(item.offerExpiresAt) }}</small><button v-if="!admin && cancellable" class="danger-button" :disabled="!!busyId" @click="cancel(item)">{{ busyId === item.id ? '取消中…' : '取消预约' }}</button><small v-else-if="!admin && item.status === 'CONFIRMED'" class="muted">已开始，不可取消</small></div>
      </article>
    </div>
  </section>
</template>
