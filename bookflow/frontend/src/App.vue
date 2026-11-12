<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getJson, writeJson } from './api'
import CatalogPage from './components/CatalogPage.vue'
import LoginDialog from './components/LoginDialog.vue'
import ReservationList from './components/ReservationList.vue'
import AdminPanel from './components/AdminPanel.vue'
import WaitlistList from './components/WaitlistList.vue'
const user = ref(null)
const checking = ref(true)
const page = ref('catalog')
const showLogin = ref(false)
const loggingOut = ref(false)
const message = ref('')
const error = ref('')
const pageTitle = computed(() => ({ catalog: '设备与时段', mine: '我的预约', waitlist: '我的候补', admin: '管理工作台' })[page.value])
function navigate(target) {
  if (target !== 'catalog' && !user.value) { showLogin.value = true; return }
  if (target === 'admin' && user.value?.role !== 'ADMIN') return
  page.value = target
}
function loggedIn(account) {
  user.value = account; showLogin.value = false; error.value = ''
  message.value = `欢迎回来，${account.displayName}。`
}
async function logout() {
  loggingOut.value = true; error.value = ''
  try {
    await writeJson('/api/auth/logout', 'POST')
    user.value = null; page.value = 'catalog'; message.value = '已退出登录。'
  } catch (reason) { error.value = reason.message }
  finally { loggingOut.value = false }
}
function expired() {
  user.value = null; page.value = 'catalog'; showLogin.value = true
  message.value = ''; error.value = '登录已失效，请重新登录。'
}
onMounted(async () => {
  window.addEventListener('bookflow:session-expired', expired)
  try { user.value = await getJson('/api/auth/me') }
  catch (reason) { if (reason.status !== 401) error.value = reason.message }
  finally { checking.value = false }
})
onUnmounted(() => window.removeEventListener('bookflow:session-expired', expired))
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="/" aria-label="BookFlow 首页"><span class="brand-symbol">b<span>f</span></span><span>BookFlow<small>LAB RESOURCES</small></span></a>
      <div class="workspace-label">实验室工作台</div>
      <nav class="workspace-nav" aria-label="主要导航">
        <button :class="page === 'catalog' ? 'nav-active' : 'nav-link'" @click="navigate('catalog')"><span aria-hidden="true">▦</span>设备与时段</button>
        <button :class="page === 'mine' ? 'nav-active' : 'nav-link'" :disabled="checking" @click="navigate('mine')"><span aria-hidden="true">▤</span>我的预约</button>
        <button :class="page === 'waitlist' ? 'nav-active' : 'nav-link'" :disabled="checking" @click="navigate('waitlist')"><span aria-hidden="true">◷</span>我的候补</button>
        <button v-if="user?.role === 'ADMIN'" :class="page === 'admin' ? 'nav-active' : 'nav-link'" @click="navigate('admin')"><span aria-hidden="true">⚙</span>管理工作台</button>
      </nav>
      <div class="sidebar-bottom"><span class="phase-dot"></span>M4 · 预约与可靠候补<p>从一台设备、一个时段开始。</p></div>
    </aside>
    <main>
      <header class="topbar"><span>工作台 <span class="breadcrumb">/ {{ pageTitle }}</span></span><div class="account-menu"><span v-if="checking">正在恢复登录…</span><template v-else-if="user"><span>{{ user.displayName }} <span class="demo-tag">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span><span v-if="user.demo" class="demo-tag">演示</span></span><button class="text-button" :disabled="loggingOut" @click="logout">{{ loggingOut ? '退出中…' : '退出' }}</button></template><button v-else class="primary-button" @click="showLogin = true">登录</button></div></header>
      <section class="page-content">
        <div class="page-heading"><div><div class="eyebrow">{{ page === 'admin' ? 'MANAGE THE LAB' : 'EXPLORE THE LAB' }}</div><h1>{{ page === 'catalog' ? '让每一次实验，有序开始。' : pageTitle }}</h1><p>{{ page === 'catalog' ? '选择设备和固定时段，预约下一次实验。' : '让资源安排清晰可查，让每一次变更留有记录。' }}</p></div><span class="heading-mark" aria-hidden="true">↗</span></div>
        <p v-if="message" class="feedback success" role="status">{{ message }} <button class="text-button" aria-label="关闭提示" @click="message = ''">×</button></p>
        <p v-if="error" class="feedback error" role="alert">{{ error }} <button class="text-button" aria-label="关闭错误提示" @click="error = ''">×</button></p>
        <div v-if="page === 'catalog'" class="milestone-note"><span class="note-icon">i</span><p>北京时间 · 时段开始前可预约和取消。取消保留历史，再次预约会生成新记录。</p><span class="data-label">BOOKFLOW / M4</span></div>
        <CatalogPage v-if="page === 'catalog'" :key="user?.id ?? 'guest'" :user="user" @login="showLogin = true" @mine="navigate('mine')" @waitlist="navigate('waitlist')" />
        <ReservationList v-else-if="page === 'mine' && user" :key="user.id" :user="user" />
        <WaitlistList v-else-if="page === 'waitlist' && user" :key="user.id" :user="user" />
        <AdminPanel v-else-if="page === 'admin' && user?.role === 'ADMIN'" :key="user.id" :user="user" />
        <footer><span>BookFlow · 实验室设备预约与候补调度平台</span><span>北京时间 · Asia/Shanghai</span></footer>
      </section>
    </main>
    <LoginDialog v-if="showLogin" @close="showLogin = false" @logged-in="loggedIn" />
  </div>
</template>
