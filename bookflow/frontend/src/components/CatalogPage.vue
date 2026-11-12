<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getJson, intentWrite } from '../api'
const props = defineProps({ user: Object })
const emit = defineEmits(['login', 'mine', 'waitlist'])
const busySlotId = ref(null)
const actionError = ref('')
const feedback = ref('')

const devices = ref([])
const selectedDeviceId = ref(null)
const devicesLoading = ref(true)
const devicesError = ref('')
const slots = ref([])
const slotsLoading = ref(false)
const slotsError = ref('')
const search = ref('')
// 明确使用实验室时区，不依赖浏览器所在时区；不把无偏移的数据库时间当作 UTC。
const dateParts = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
}).formatToParts(new Date())
const part = (type) => dateParts.find((item) => item.type === type).value
const today = `${part('year')}-${part('month')}-${part('day')}`
const date = ref(today)
const selectedDevice = computed(() => devices.value.find((item) => item.id === selectedDeviceId.value))
const filteredDevices = computed(() => devices.value.filter((item) =>
  `${item.name} ${item.code} ${item.category} ${item.location}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const activeCount = computed(() => devices.value.filter((item) => item.status === 'ACTIVE').length)
const availabilityLabels = {
  AVAILABLE: '开放', OCCUPIED: '已占用', CLOSED: '未开放', STARTED: '已开始', ENDED: '已结束',
}
let slotsRequest
let requestSequence = 0

async function loadDevices() {
  devicesLoading.value = true
  devicesError.value = ''
  try {
    devices.value = await getJson('/api/devices')
    const nextId = devices.value.some((item) => item.id === selectedDeviceId.value)
      ? selectedDeviceId.value : devices.value[0]?.id ?? null
    if (nextId === selectedDeviceId.value && nextId !== null) await loadSlots()
    else selectedDeviceId.value = nextId
  } catch (error) {
    devices.value = []
    selectedDeviceId.value = null
    devicesError.value = error.message || '连接失败，请检查本地后端。'
  } finally {
    devicesLoading.value = false
  }
}

async function loadSlots() {
  slotsRequest?.abort()
  const sequence = ++requestSequence
  slots.value = []
  slotsError.value = ''
  slotsLoading.value = false
  if (!selectedDeviceId.value || !date.value) return
  slotsRequest = new AbortController()
  slotsLoading.value = true
  try {
    const result = await getJson(`/api/devices/${selectedDeviceId.value}/slots?date=${encodeURIComponent(date.value)}`, slotsRequest.signal)
    // 快速切换设备/日期时，旧请求不得覆盖新选择。
    if (sequence === requestSequence) slots.value = result.slots
  } catch (error) {
    if (error.name !== 'AbortError' && sequence === requestSequence) slotsError.value = error.message
  } finally {
    if (sequence === requestSequence) slotsLoading.value = false
  }
}

watch([selectedDeviceId, date], loadSlots)
onMounted(loadDevices)

async function bookSlot(slot) {
  if (!props.user) { emit('login'); return }
  if (busySlotId.value) return
  busySlotId.value = slot.id
  feedback.value = ''; actionError.value = ''
  try {
    const result = await intentWrite(props.user.id, '/api/reservations', slot.id, { slotId: slot.id })
    feedback.value = `预约请求已处理，记录编号 #${result.reservation.id}。`
    await loadSlots()
  } catch (reason) {
    actionError.value = reason.message
    if (reason.status === 409) await loadSlots()
  } finally { busySlotId.value = null }
}
async function joinWaitlist(slot) {
  if (!props.user) { emit('login'); return }
  if (busySlotId.value) return
  busySlotId.value = slot.id; feedback.value = ''; actionError.value = ''
  try {
    await intentWrite(props.user.id, '/api/waitlist', slot.id, { slotId: slot.id })
    emit('waitlist')
  } catch (reason) { actionError.value = reason.message; if (reason.status === 409) await loadSlots() }
  finally { busySlotId.value = null }
}
onUnmounted(() => slotsRequest?.abort())
</script>

<template>
        <div class="overview">
          <div><span>设备总数</span><strong>{{ devicesLoading || devicesError ? '—' : devices.length }}<small>台</small></strong></div>
          <div><span>开放设备</span><strong>{{ devicesLoading || devicesError ? '—' : activeCount }}<small>台</small></strong></div>
          <div class="timezone"><span>实验室时区</span><strong>Asia/Shanghai<small>UTC +08:00</small></strong></div>
        </div>

        <div class="catalog-layout">
          <section class="device-panel" aria-labelledby="device-heading">
            <div class="section-heading"><h2 id="device-heading">设备目录 <span>Equipment</span></h2><button class="text-button" @click="loadDevices" :disabled="devicesLoading">刷新</button></div>
            <label class="search-box"><span aria-hidden="true">⌕</span><input v-model="search" type="search" placeholder="搜索设备、编号或位置" aria-label="搜索设备、编号或位置"></label>
            <div v-if="devicesLoading" class="state-box" role="status"><span class="spinner"></span>正在读取设备…</div>
            <div v-else-if="devicesError" class="state-box error" role="alert"><strong>暂时无法连接设备目录</strong><p>{{ devicesError }}</p><button class="secondary-button" @click="loadDevices">重新连接</button></div>
            <div v-else-if="!devices.length" class="state-box"><strong>还没有设备</strong><p>数据库中尚无设备记录。可按 README 显式准备演示数据。</p></div>
            <div v-else-if="!filteredDevices.length" class="state-box"><strong>没有匹配的设备</strong><p>试试设备名称、编号或实验室位置。</p><button class="text-button" @click="search = ''">清除搜索</button></div>
            <div v-else class="device-list">
              <button v-for="device in filteredDevices" :key="device.id" class="device-card" :class="{ selected: device.id === selectedDeviceId }" :aria-pressed="device.id === selectedDeviceId" @click="selectedDeviceId = device.id">
                <div class="card-top"><span class="equipment-code">{{ device.code }}</span><span class="status-pill" :class="{ inactive: device.status !== 'ACTIVE' }"><i></i>{{ device.status === 'ACTIVE' ? '开放中' : '已停用' }}</span></div>
                <h3>{{ device.name }}</h3><p class="device-meta">{{ device.category }} <span>·</span> {{ device.location }}</p>
                <div class="card-bottom"><span v-if="device.demo" class="demo-tag">演示设备</span><span v-else class="real-tag">实验室设备</span><span class="card-action">查看时段 <span aria-hidden="true">↗</span></span></div>
              </button>
            </div>
          </section>

          <section class="slots-panel" aria-labelledby="slots-heading" :aria-busy="slotsLoading">
            <div class="section-heading"><h2 id="slots-heading">开放时段 <span>Schedule</span><span class="live-dot" aria-hidden="true"></span></h2></div>
            <template v-if="selectedDevice">
              <div class="selected-info"><span class="eyebrow">SELECTED EQUIPMENT</span><h3>{{ selectedDevice.name }}</h3><p>{{ selectedDevice.description }}</p><div class="location-line"><span aria-hidden="true">⌖</span> {{ selectedDevice.location }}<span v-if="selectedDevice.demo" class="demo-tag">演示</span></div></div>
              <div class="date-row"><label for="slot-date">查看日期</label><input id="slot-date" v-model="date" type="date" min="1000-01-01" max="9998-12-31"><button class="text-button" @click="date = today" :disabled="date === today">今天</button></div>
              <div v-if="!date" class="state-box">请选择一个日期</div>
              <div v-else-if="slotsLoading" class="state-box" role="status"><span class="spinner"></span>正在读取当日时段…</div>
              <div v-else-if="slotsError" class="state-box error" role="alert"><strong>时段加载失败</strong><p>{{ slotsError }}</p><button class="secondary-button" @click="loadSlots">重试</button></div>
              <template v-else>
                <div v-if="selectedDevice.status !== 'ACTIVE'" class="inactive-note">此设备已停用，暂不开放新预约。</div>
                <div v-if="!slots.length" class="state-box empty-slots"><span aria-hidden="true">▦</span><strong>当天暂无开放时段</strong><p>可以切换日期，或选择其他设备查看。</p></div>
                <div v-else class="slot-list">
                  <div class="slot-list-heading"><span>当日固定时段</span><span>{{ slots.length }} 个时段</span></div>
                  <div v-for="slot in slots" :key="slot.id" class="slot-row"><div class="slot-time"><span class="time-line"></span><strong>{{ slot.startAt.slice(11, 16) }} <span>—</span> {{ slot.endAt.slice(11, 16) }}</strong><small v-if="slot.demo">演示时段</small></div><div class="slot-actions"><span class="slot-status" :class="slot.availability.toLowerCase()">{{ availabilityLabels[slot.availability] || slot.availability }}</span><button v-if="slot.availability === 'AVAILABLE'" class="primary-button" :disabled="!!busySlotId" @click="bookSlot(slot)">{{ busySlotId === slot.id ? '提交中…' : user ? '预约' : '登录预约' }}</button><button v-else-if="slot.availability === 'OCCUPIED'" class="secondary-button" :disabled="!!busySlotId" @click="joinWaitlist(slot)">{{ busySlotId === slot.id ? '提交中…' : user ? '加入候补' : '登录候补' }}</button></div></div>
                </div>
              </template>
              <p v-if="feedback" class="feedback success" role="status">{{ feedback }} <button class="text-button" @click="emit('mine')">查看我的预约</button></p>
              <p v-if="actionError" class="feedback error" role="alert">{{ actionError }} <button v-if="user" class="text-button" @click="emit('mine')">查看我的预约</button></p>
              <div class="schedule-footnote"><span aria-hidden="true">◷</span><p>开放状态为本次查询结果，提交时由后端重新校验。开始后不能新建或取消预约。满额可候补，获邀后需在期限内确认。</p></div>
            </template>
            <div v-else class="state-box">选择一台设备，查看它的开放时段。</div>
          </section>
        </div>
</template>
