<script setup>
import { nextTick, ref } from 'vue'
import { clearAiSession, sendAiChatMessage, streamAiChatMessage } from '../api/ai'
import { getErrorMessage } from '../api/request'

const sessionId = ref(getOrCreateSessionId())
const messages = ref([
  {
    role: 'assistant',
    content: '你好，我可以帮你查询演出、查看订单状态、设置开抢提醒。需要抢票时，我会帮你提交抢票请求，请及时查看订单结果。'
  }
])
const input = ref('')
const loading = ref(false)
const error = ref('')
const chatBody = ref(null)

const quickPrompts = [
  '帮我找周杰伦上海站的票',
  '查询我的订单状态',
  '帮我设置开抢提醒'
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
      assistantMessage.content = '抱歉，AI 客服暂时不可用，请稍后再试。'
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
      content: '已开始新的客服会话。你可以继续查询演出、订单或提醒。'
    }
  ]
  await scrollToBottom()
}
</script>

<template>
  <section class="page-head">
    <div>
      <p class="eyebrow">AI 客服</p>
      <h1>票务助手</h1>
    </div>
    <button class="secondary-button" type="button" @click="resetSession">新会话</button>
  </section>

  <section class="ai-layout">
    <div class="panel chat-panel">
      <div ref="chatBody" class="chat-body">
        <article
          v-for="(message, index) in messages"
          :key="index"
          class="chat-message"
          :class="message.role"
        >
          <span>{{ message.role === 'user' ? '我' : 'AI 客服' }}</span>
          <p>{{ message.content }}</p>
        </article>
      </div>

      <p v-if="error" class="notice error">{{ error }}</p>

      <form class="chat-input" @submit.prevent="sendMessage()">
        <input
          v-model.trim="input"
          :disabled="loading"
          placeholder="输入你的票务问题，例如：帮我抢周杰伦上海站580元票"
        />
        <button class="primary-button" type="submit" :disabled="loading || !input.trim()">
          {{ loading ? '发送中' : '发送' }}
        </button>
      </form>
    </div>

    <aside class="panel ai-side">
      <div class="section-title">
        <div>
          <p class="eyebrow">可用能力</p>
          <h2>让客服代办</h2>
        </div>
      </div>

      <div class="ai-capabilities">
        <button
          v-for="prompt in quickPrompts"
          :key="prompt"
          class="secondary-button full"
          type="button"
          :disabled="loading"
          @click="sendMessage(prompt)"
        >
          {{ prompt }}
        </button>
      </div>

      <p class="muted">
        AI 客服会按正常购票流程处理请求，不会绕过排队、限购和库存校验。
      </p>
    </aside>
  </section>
</template>
