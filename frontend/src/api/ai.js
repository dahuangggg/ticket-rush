import request from './request'

export function sendAiChatMessage(sessionId, message) {
  return request.post('/ai/chat', { sessionId, message })
}

export async function streamAiChatMessage(sessionId, message, handlers = {}) {
  const response = await fetch('/api/ai/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authorizationHeader()
    },
    body: JSON.stringify({ sessionId, message })
  })

  if (!response.ok || !response.body) {
    throw new Error(`AI stream failed with status ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const eventText of events) {
      handleSseEvent(eventText, handlers)
    }
  }

  if (buffer.trim()) {
    handleSseEvent(buffer, handlers)
  }
}

export function clearAiSession(sessionId) {
  return request.delete(`/ai/sessions/${sessionId}`)
}

function authorizationHeader() {
  const token = localStorage.getItem('accessToken')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function handleSseEvent(eventText, handlers) {
  const eventName = eventText
    .split('\n')
    .find((line) => line.startsWith('event:'))
    ?.slice('event:'.length)
    .trim() || 'message'
  const data = eventText
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice('data:'.length).replace(/^ /, ''))
    .join('\n')
  const decoded = decodeBase64(data)

  if (eventName === 'delta') handlers.onDelta?.(decoded)
  if (eventName === 'error') handlers.onError?.(decoded)
  if (eventName === 'done') handlers.onDone?.()
}

function decodeBase64(value) {
  if (!value) return ''

  const bytes = Uint8Array.from(atob(value), (char) => char.charCodeAt(0))
  return new TextDecoder().decode(bytes)
}
