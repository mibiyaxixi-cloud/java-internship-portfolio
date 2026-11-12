<script setup>
import { onMounted, ref } from 'vue'
import { login } from '../api'
const emit = defineEmits(['close', 'logged-in'])
const dialog = ref(null)
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
onMounted(() => dialog.value.showModal())
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { emit('logged-in', await login(username.value.trim(), password.value)) }
  catch (reason) { error.value = reason.message }
  finally { password.value = ''; busy.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="form-dialog login-dialog" aria-labelledby="login-title" @cancel.prevent="!busy && emit('close')">
    <div class="dialog-heading"><div><span class="eyebrow">WELCOME TO BOOKFLOW</span><h2 id="login-title">登录实验室工作台</h2></div><button class="icon-button" aria-label="关闭登录" :disabled="busy" @click="emit('close')">×</button></div>
    <p class="muted">登录后即可预约设备，并查看和管理自己的预约。</p>
    <form @submit.prevent="submit">
      <label>账号<input v-model="username" autocomplete="username" required maxlength="64" autofocus :disabled="busy"></label>
      <label>密码<input v-model="password" type="password" autocomplete="current-password" required maxlength="72" :disabled="busy"></label>
      <p v-if="error" class="feedback error" role="alert">{{ error }}</p>
      <button class="primary-button full-width" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
    </form>
    <p class="form-hint">会话过期或服务重启后需要重新登录。演示账号仅用于本地练习。</p>
  </dialog>
</template>
