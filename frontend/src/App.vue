<script setup>
import { useRouter } from 'vue-router'
import { logout as logoutRequest } from './api/auth'
import { clearAuth, currentRefreshToken, isLoggedIn } from './stores/auth'

const router = useRouter()

async function handleLogout() {
  const refreshToken = currentRefreshToken()
  try {
    if (refreshToken) {
      await logoutRequest(refreshToken)
    }
  } catch {
    // 本地退出优先，服务端 refreshToken 清理失败不阻塞用户离开当前登录态。
  } finally {
    clearAuth()
    router.push('/events')
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/events">
        <span class="brand-mark">TR</span>
        <span>ticket-rush</span>
      </RouterLink>

      <nav class="nav">
        <RouterLink to="/events">演出</RouterLink>
        <RouterLink to="/orders">我的订单</RouterLink>
        <RouterLink to="/ai">AI 客服</RouterLink>
        <button v-if="isLoggedIn" class="link-button" type="button" @click="handleLogout">退出</button>
        <RouterLink v-else to="/login">登录</RouterLink>
      </nav>
    </header>

    <main class="main">
      <RouterView />
    </main>
  </div>
</template>
