<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getJson, writeJson } from '../api'
import ReservationList from './ReservationList.vue'
import WaitlistList from './WaitlistList.vue'
defineProps({ user: Object })
const tab = ref('devices')
const devices = ref([])
const loading = ref(false)
const error = ref('')
const feedback = ref('')
const busy = ref(false)
const deviceId = ref('')
const day = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
const date = ref(day)
const slots = ref([])
const slotsLoading = ref(false)
const selectedDevice = computed(() => devices.value.find((device) => device.id === Number(deviceId.value)))
const deviceForm = ref(null)
const slotForm = ref(null)
let slotsSequence = 0

async function loadDevices() {
  loading.value = true
  error.value = ''
  try {
    devices.value = await getJson('/api/devices')
    if (!devices.value.some((device) => device.id === Number(deviceId.value))) deviceId.value = devices.value[0]?.id ?? ''
  } catch (reason) { devices.value = []; deviceId.value = ''; error.value = reason.message }
  finally { loading.value = false }
}
async function loadSlots() {
  const sequence = ++slotsSequence
  slots.value = []
  slotsLoading.value = false
  if (!deviceId.value || !date.value) return
  slotsLoading.value = true
  error.value = ''
  try {
    const result = await getJson(`/api/admin/devices/${deviceId.value}/slots?date=${encodeURIComponent(date.value)}`)
    if (sequence === slotsSequence) slots.value = result
  } catch (reason) { if (sequence === slotsSequence) error.value = reason.message }
  finally { if (sequence === slotsSequence) slotsLoading.value = false }
}
function editDevice(device) {
  feedback.value = ''; error.value = ''; slotForm.value = null
  deviceForm.value = device
    ? { id: device.id, code: device.code, name: device.name, category: device.category, location: device.location, description: device.description, status: device.status }
    : { code: '', name: '', category: '', location: '', description: '', status: 'ACTIVE' }
}
function editSlot(slot) {
  feedback.value = ''; error.value = ''; deviceForm.value = null
  slotForm.value = slot
    ? { id: slot.id, deviceId: slot.deviceId, startAt: slot.startAt.slice(0, 16), endAt: slot.endAt.slice(0, 16), status: slot.status, hasHistory: slot.hasHistory }
    : { deviceId: Number(deviceId.value), startAt: `${date.value}T09:00`, endAt: `${date.value}T10:30`, status: 'OPEN', hasHistory: false }
}
async function perform(work, message) {
  if (busy.value) return
  busy.value = true; error.value = ''; feedback.value = ''
  try { await work(); feedback.value = message; return true }
  catch (reason) { error.value = reason.message; return false }
  finally { busy.value = false }
}
async function saveDevice() {
  const { id, ...input } = deviceForm.value
  if (await perform(() => writeJson(id ? `/api/admin/devices/${id}` : '/api/admin/devices', id ? 'PUT' : 'POST', input), '设备已保存。')) {
    deviceForm.value = null
    await loadDevices()
  }
}
async function deleteDevice(device) {
  if (!window.confirm(`删除设备「${device.name}」？仅无关联时段的设备可删除。`)) return
  if (await perform(() => writeJson(`/api/admin/devices/${device.id}`, 'DELETE'), '设备已删除。')) await loadDevices()
}
async function saveSlot() {
  const { id, deviceId: targetDeviceId, startAt, endAt, status } = slotForm.value
  if (await perform(() => writeJson(id ? `/api/admin/slots/${id}` : `/api/admin/devices/${targetDeviceId}/slots`, id ? 'PUT' : 'POST', { startAt, endAt, status }), '时段已保存。')) {
    slotForm.value = null
    await loadSlots()
  }
}
async function deleteSlot(slot) {
  if (!window.confirm('删除这个时段？有任何预约或候补历史的时段不能删除。')) return
  if (await perform(() => writeJson(`/api/admin/slots/${slot.id}`, 'DELETE'), '时段已删除。')) await loadSlots()
}
watch([deviceId, date], () => { slotForm.value = null; loadSlots() })
watch(tab, () => { deviceForm.value = null; slotForm.value = null; feedback.value = ''; error.value = ''; if (tab.value === 'slots') loadSlots() })
onMounted(loadDevices)
</script>

<template>
  <section class="admin-panel">
    <div class="tabs" aria-label="管理分类"><button v-for="item in [{ key: 'devices', label: '设备管理' }, { key: 'slots', label: '时段管理' }, { key: 'reservations', label: '预约记录' }, { key: 'waitlist', label: '候补与任务' }]" :key="item.key" :class="{ active: tab === item.key }" :aria-pressed="tab === item.key" :disabled="busy" @click="tab = item.key">{{ item.label }}</button></div>
    <ReservationList v-if="tab === 'reservations'" admin />
    <WaitlistList v-else-if="tab === 'waitlist'" admin :user="user" />
    <template v-else>
      <p v-if="feedback" class="feedback success" role="status">{{ feedback }}</p>
      <div v-if="error" class="feedback error" role="alert">{{ error }} <button class="text-button" @click="tab === 'devices' ? loadDevices() : loadSlots()">刷新数据</button></div>
      <template v-if="tab === 'devices'">
        <div class="section-heading"><h2>设备管理</h2><div><button class="text-button" :disabled="loading || busy" @click="loadDevices">刷新</button><button class="primary-button" :disabled="busy" @click="editDevice(null)">新增设备</button></div></div>
        <p class="muted">编号创建后不可修改。有未结束预约、确认资格或候补时不能停用；有关联时段的设备不能删除。</p>
        <form v-if="deviceForm" class="editor-form" @submit.prevent="saveDevice">
          <h3>{{ deviceForm.id ? '编辑设备' : '新增设备' }}</h3>
          <div class="form-grid"><label>设备编号<input v-model="deviceForm.code" required minlength="2" maxlength="40" pattern="[A-Za-z0-9][A-Za-z0-9-]{1,39}" :disabled="!!deviceForm.id || busy" placeholder="例如 LAB-MIC-02"></label><label>设备名称<input v-model="deviceForm.name" required maxlength="100" :disabled="busy"></label><label>类别<input v-model="deviceForm.category" required maxlength="60" :disabled="busy"></label><label>实验室位置<input v-model="deviceForm.location" required maxlength="100" :disabled="busy"></label><label>状态<select v-model="deviceForm.status" :disabled="busy"><option value="ACTIVE">开放</option><option value="INACTIVE">停用</option></select></label></div>
          <label>设备说明<textarea v-model="deviceForm.description" maxlength="500" rows="3" :disabled="busy"></textarea></label>
          <div class="form-actions"><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存设备' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="deviceForm = null">取消编辑</button></div>
        </form>
        <div v-if="loading" class="state-box" role="status">正在读取设备…</div>
        <div v-else-if="!devices.length && !error" class="state-box">暂无设备，请新增。</div>
        <div v-else class="management-list"><article v-for="device in devices" :key="device.id" class="management-row"><div><span class="equipment-code">{{ device.code }}</span><h3>{{ device.name }} <span v-if="device.demo" class="demo-tag">演示</span></h3><p class="muted">{{ device.category }} · {{ device.location }} · {{ device.status === 'ACTIVE' ? '开放' : '停用' }}</p></div><div class="row-actions"><button class="secondary-button" :disabled="busy" @click="editDevice(device)">编辑</button><button class="danger-button" :disabled="busy" @click="deleteDevice(device)">删除</button></div></article></div>
      </template>
      <template v-else>
        <div class="section-heading"><h2>固定时段管理</h2><button class="primary-button" :disabled="!deviceId || !date || busy" @click="editSlot(null)">新增时段</button></div>
        <p class="muted">北京时间，按分钟设置。相邻时段允许，重叠时段拒绝；有预约或候补历史的时段不能改时间或删除。</p>
        <div class="filter-row"><label>设备<select v-model="deviceId" :disabled="busy || loading"><option value="" disabled>请选择设备</option><option v-for="device in devices" :key="device.id" :value="device.id">{{ device.name }}（{{ device.code }}）</option></select></label><label>开始日期<input v-model="date" type="date" min="1000-01-01" max="9998-12-31" :disabled="busy"></label><button class="text-button" :disabled="slotsLoading || busy" @click="loadSlots">刷新时段</button></div>
        <form v-if="slotForm" class="editor-form" @submit.prevent="saveSlot">
          <h3>{{ slotForm.id ? '编辑时段' : '新增时段' }} · {{ selectedDevice?.name }}</h3>
          <div class="form-grid"><label>开始时间<input v-model="slotForm.startAt" type="datetime-local" step="60" required :disabled="busy || slotForm.hasHistory"></label><label>结束时间<input v-model="slotForm.endAt" type="datetime-local" step="60" required :disabled="busy || slotForm.hasHistory"></label><label>状态<select v-model="slotForm.status" :disabled="busy"><option value="OPEN">开放</option><option value="CLOSED">关闭</option></select></label></div>
          <p v-if="slotForm.hasHistory" class="form-hint">已有预约或候补历史，起止时间锁定。存在有效预约时不能关闭。</p>
          <div class="form-actions"><button class="primary-button" :disabled="busy">{{ busy ? '保存中…' : '保存时段' }}</button><button type="button" class="secondary-button" :disabled="busy" @click="slotForm = null">取消编辑</button></div>
        </form>
        <div v-if="slotsLoading || loading" class="state-box" role="status">正在读取时段…</div>
        <div v-else-if="!slots.length && !error" class="state-box">当前设备在所选日期没有时段。</div>
        <div v-else class="management-list"><article v-for="slot in slots" :key="slot.id" class="management-row"><div><h3>{{ slot.startAt.replace('T', ' ').slice(0, 16) }} — {{ slot.endAt.replace('T', ' ').slice(0, 16) }}</h3><p class="muted">{{ slot.status === 'OPEN' ? '开放' : '关闭' }} · {{ slot.occupied ? '已被预约' : '无有效预约' }}<template v-if="slot.hasHistory"> · 有预约或候补历史</template><span v-if="slot.demo" class="demo-tag">演示</span></p></div><div class="row-actions"><button class="secondary-button" :disabled="busy" @click="editSlot(slot)">编辑</button><button class="danger-button" :disabled="busy || slot.hasHistory" :title="slot.hasHistory ? '有预约或候补历史不能删除' : '删除时段'" @click="deleteSlot(slot)">删除</button></div></article></div>
      </template>
    </template>
  </section>
</template>
