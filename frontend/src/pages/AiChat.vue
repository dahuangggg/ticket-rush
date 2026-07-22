<script setup>
import { nextTick, ref } from 'vue'
import { clearAiSession, sendAiChatMessage, streamAiChatMessage } from '../api/ai'
import { getErrorMessage } from '../api/request'

const sessionId = ref(getOrCreateSessionId())
const messages = ref([
  {
    role: 'assistant',
    content: '你好，我是只读票务助手，可以查询演出、票档和你的订单状态。抢票、支付、取消等操作请在对应业务页面完成。'
  }
])
const input = ref('')
const loading = ref(false)
const error = ref('')
const chatBody = ref(null)

const quickPrompts = [
  '上海近期有哪些演出？',
  '周杰伦上海站有哪些票档？',
  '我的待支付订单有哪些？'
]

function getOrCreateSessionId() {
  const existing = localStorage.getItem('aiSessionId')
  if (existing) return existing

  const next = createSessionId()
  localStorage.setItem('aiSessionId', next)
  return next
}

function createSessionId() {
  return globalThis.crypto?.randomUUID?.() || `ai-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function scrollToBottom() {
  await nextTick()
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
}

async function sendMessage(text = input.value) {
  const content = text.trim()
  if (!content || loading.value) return

  input.value = ''
  error.value = ''
  messages.value.push({ role: 'user', content })
  messages.value.push({ role: 'assistant', content: '' })
  const assistantMessage = messages.value[messages.value.length - 1]
  loading.value = true
  await scrollToBottom()

  try {
    await streamAiChatMessage(sessionId.value, content, {
      onDelta: async (token) => {
        assistantMessage.content += token
        await scrollToBottom()
      },
      onError: (message) => {
        throw new Error(message || 'AI 客服请求失败')
      }
    })
    if (!assistantMessage.content) {
      assistantMessage.content = 'AI 服务没有返回内容'
    }
  } catch (err) {
    try {
      const { data } = await sendAiChatMessage(sessionId.value, content)
      assistantMessage.content = data.answer || 'AI 服务没有返回内容'
    } catch (fallbackErr) {
      error.value = getErrorMessage(fallbackErr, err.message || 'AI 客服请求失败')
      assistantMessage.content = '抱歉，只读助手暂时不可用，请稍后再试。'
    }
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

async function resetSession() {
  error.value = ''
  try {
    await clearAiSession(sessionId.value)
  } catch {
    // 清理会话失败不阻塞本地新会话，下一条消息会使用新的 sessionId。
  }

  const next = createSessionId()
  localStorage.setItem('aiSessionId', next)
  sessionId.value = next
  messages.value = [
    {
      role: 'assistant',
      content: '已开始新的会话。你可以继续查询演出、票档或自己的订单。'
    }
  ]
  await scrollToBottom()
}
</script>

<template>
  <section class="page-head ai-page-head">
    <div>
      <div class="ai-heading-row">
        <p class="eyebrow">RUSH INTELLIGENCE</p>
        <span class="readonly-pill"><i></i> 只读模式</span>
      </div>
      <h1>问演出，也问订单。</h1>
      <p class="section-lede">可以查询演出、票档和你自己的订单。不会代你抢票或操作订单。</p>
    </div>
    <button class="secondary-button" type="button" @click="resetSession">新会话</button>
  </section>

  <section class="ai-layout">
    <div class="panel chat-panel">
      <div class="chat-topline">
        <div class="assistant-identity">
          <span class="assistant-orb" aria-hidden="true">✦</span>
          <div>
            <strong>Rush Assistant</strong>
            <small><i></i> 在线 · 只读</small>
          </div>
        </div>
        <span class="session-label">PRIVATE SESSION</span>
      </div>

      <div ref="chatBody" class="chat-body" aria-live="polite">
        <article
          v-for="(message, index) in messages"
          :key="index"
          class="chat-message"
          :class="message.role"
        >
          <span>{{ message.role === 'user' ? '你' : '只读助手' }}</span>
          <p>
            <template v-if="message.content">{{ message.content }}</template>
            <span v-else class="typing-dots" aria-label="正在回复"><i></i><i></i><i></i></span>
          </p>
        </article>
      </div>

      <p v-if="error" class="notice error" role="alert">{{ error }}</p>

      <form class="chat-input" @submit.prevent="sendMessage()">
        <input
          v-model.trim="input"
          :disabled="loading"
          aria-label="询问只读票务助手"
          placeholder="询问演出、票档或我的订单…"
        />
        <button class="send-button" type="submit" :disabled="loading || !input.trim()" aria-label="发送消息">
          <span aria-hidden="true">↑</span>
        </button>
      </form>
      <p class="composer-note">助手只能读取你的票务信息，关键操作仍由你完成。</p>
    </div>

    <aside class="ai-side">
      <section class="panel prompt-panel">
        <div class="section-title">
          <div>
            <p class="eyebrow">TRY ASKING</p>
            <h2>你可以这样问</h2>
          </div>
        </div>

        <div class="ai-capabilities">
          <button
            v-for="(prompt, index) in quickPrompts"
            :key="prompt"
            class="prompt-button"
            type="button"
            :disabled="loading"
            @click="sendMessage(prompt)"
          >
            <span>0{{ index + 1 }}</span>
            <strong>{{ prompt }}</strong>
            <i aria-hidden="true">↗</i>
          </button>
        </div>
      </section>

      <section class="ai-safety-card">
        <span class="safety-mark" aria-hidden="true">✓</span>
        <div>
          <strong>你的决定，由你确认</strong>
          <p>AI 不会抢票、支付、取消订单、变更库存或设置提醒。</p>
        </div>
      </section>
    </aside>
  </section>
</template>
