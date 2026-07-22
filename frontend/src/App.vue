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
    <header class="shell-header">
      <div class="topbar">
        <RouterLink class="brand" to="/events" aria-label="Rush 演出首页">
          <span class="brand-mark" aria-hidden="true"><i></i></span>
          <span class="brand-copy">
            <strong>Rush</strong>
            <small>Live, in your hands.</small>
          </span>
        </RouterLink>

        <nav class="desktop-nav" aria-label="主导航">
          <RouterLink to="/events">发现演出</RouterLink>
          <RouterLink to="/orders">我的订单</RouterLink>
          <RouterLink to="/ai">只读助手</RouterLink>
        </nav>

        <div class="account-action">
          <button v-if="isLoggedIn" class="text-button" type="button" @click="handleLogout">
            退出
          </button>
          <RouterLink v-else class="nav-login" to="/login">登录</RouterLink>
        </div>
      </div>
    </header>

    <main class="main">
      <RouterView />
    </main>

    <nav class="mobile-dock" aria-label="移动端主导航">
      <RouterLink to="/events">
        <span class="dock-icon" aria-hidden="true">⌂</span>
        <span>演出</span>
      </RouterLink>
      <RouterLink to="/orders">
        <span class="dock-icon" aria-hidden="true">▤</span>
        <span>订单</span>
      </RouterLink>
      <RouterLink to="/ai">
        <span class="dock-icon" aria-hidden="true">✦</span>
        <span>助手</span>
      </RouterLink>
      <RouterLink v-if="!isLoggedIn" to="/login">
        <span class="dock-icon" aria-hidden="true">●</span>
        <span>登录</span>
      </RouterLink>
      <button v-else type="button" @click="handleLogout">
        <span class="dock-icon" aria-hidden="true">↗</span>
        <span>退出</span>
      </button>
    </nav>
  </div>
</template>
