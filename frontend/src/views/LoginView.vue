<template>
  <div class="login-card">
    <h1>科研影像湖仓存储治理平台</h1>
    <div class="sub">对象目录 · 血缘 DAG · 保留 · 恢复 · 成本 · 归档</div>
    <div v-if="error" class="error">{{ error }}</div>
    <input v-model="username" placeholder="用户名" @keyup.enter="submit" />
    <input v-model="password" type="password" placeholder="密码" @keyup.enter="submit" />
    <button class="btn" @click="submit">登录</button>
    <div class="hint" style="margin-top:16px">
      测试账号：admin / admin123（管理员）<br />
      reviewer / review123（项目审阅人）
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const router = useRouter()
const username = ref('admin')
const password = ref('admin123')
const error = ref('')
async function submit() {
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push('/catalog')
  } catch (e) {
    error.value = e.response?.data?.message || '登录失败'
  }
}
</script>
