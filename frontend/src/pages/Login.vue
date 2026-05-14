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
    message.value = data.message || '验证码已发送，请查看后端日志'
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
    <div class="panel auth-panel">
      <p class="eyebrow">账号登录</p>
      <h1>手机号验证码登录</h1>

      <form class="form" @submit.prevent="handleLogin">
        <label>
          <span>手机号</span>
          <input v-model.trim="phone" inputmode="tel" maxlength="11" placeholder="请输入手机号" />
        </label>

        <label>
          <span>验证码</span>
          <div class="inline-input">
            <input v-model.trim="code" inputmode="numeric" maxlength="6" placeholder="6 位验证码" />
            <button class="secondary-button" type="button" :disabled="sending" @click="handleSendCode">
              {{ sending ? '发送中' : '获取验证码' }}
            </button>
          </div>
        </label>

        <button class="primary-button full" type="submit" :disabled="loading">
          {{ loading ? '登录中' : '登录' }}
        </button>
      </form>

      <p v-if="message" class="notice success">{{ message }}</p>
      <p v-if="error" class="notice error">{{ error }}</p>
    </div>
  </section>
</template>
