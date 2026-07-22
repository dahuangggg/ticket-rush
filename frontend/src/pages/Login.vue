<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getErrorMessage } from '../api/request'
import { login, sendSmsCode } from '../api/auth'
import { saveAuth } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const phone = ref('13800000001')
const code = ref('')
const loading = ref(false)
const sending = ref(false)
const message = ref('')
const error = ref('')

async function handleSendCode() {
  error.value = ''
  message.value = ''
  sending.value = true
  try {
    const { data } = await sendSmsCode(phone.value)
    message.value = data.message || '验证码已发送'
  } catch (err) {
    error.value = getErrorMessage(err, '验证码发送失败')
  } finally {
    sending.value = false
  }
}

async function handleLogin() {
  error.value = ''
  message.value = ''
  loading.value = true
  try {
    const { data } = await login(phone.value, code.value)
    saveAuth(data)
    router.push(route.query.redirect || '/events')
  } catch (err) {
    error.value = getErrorMessage(err, '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="auth-layout">
    <div class="auth-showcase">
      <div class="auth-showcase-copy">
        <p class="eyebrow light">RUSH MEMBER</p>
        <h1>登录后，<br />离现场更近一步。</h1>
        <p>亲自发起抢票、查看订单，并使用只读票务助手。</p>
      </div>

      <div class="ticket-preview" aria-hidden="true">
        <div class="ticket-preview-head">
          <span>RUSH / LIVE</span>
          <span>ADMIT ONE</span>
        </div>
        <div class="ticket-preview-mark">現</div>
        <div class="ticket-preview-info">
          <span>LIVE EXPERIENCE</span>
          <strong>YOUR NEXT SHOW</strong>
        </div>
        <div class="ticket-stub">
          <i v-for="index in 22" :key="index"></i>
        </div>
      </div>
    </div>

    <div class="panel auth-panel">
      <div class="auth-panel-head">
        <span class="auth-lock" aria-hidden="true">●</span>
        <div>
          <p class="eyebrow">WELCOME BACK</p>
          <h2>登录 ticket-rush</h2>
        </div>
      </div>
      <p class="auth-intro">使用手机号验证码登录。不会创建密码，也不会代你执行任何购票操作。</p>

      <form class="form" @submit.prevent="handleLogin">
        <label class="field-group">
          <span>手机号</span>
          <input v-model.trim="phone" inputmode="tel" autocomplete="tel" maxlength="11" placeholder="请输入手机号" />
        </label>

        <label class="field-group">
          <span>验证码</span>
          <div class="inline-input">
            <input v-model.trim="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6 位验证码" />
            <button class="secondary-button" type="button" :disabled="sending || phone.length !== 11" @click="handleSendCode">
              {{ sending ? '发送中…' : '获取验证码' }}
            </button>
          </div>
        </label>

        <button class="primary-button full auth-submit" type="submit" :disabled="loading || phone.length !== 11 || code.length !== 6">
          {{ loading ? '正在登录…' : '继续' }}
          <span aria-hidden="true">→</span>
        </button>
      </form>

      <p v-if="message" class="notice success" role="status">{{ message }}</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>

      <p class="demo-note">
        <span aria-hidden="true">i</span>
        本地演示不会发送真实短信，请按运行指南从 Redis 获取验证码。
      </p>
    </div>
  </section>
</template>
