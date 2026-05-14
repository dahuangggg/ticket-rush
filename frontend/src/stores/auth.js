import { computed, ref } from 'vue'

const accessToken = ref(localStorage.getItem('accessToken') || '')
const refreshToken = ref(localStorage.getItem('refreshToken') || '')

export const isLoggedIn = computed(() => Boolean(accessToken.value))

export function saveAuth(tokens) {
  accessToken.value = tokens.accessToken
  refreshToken.value = tokens.refreshToken
  localStorage.setItem('accessToken', tokens.accessToken)
  localStorage.setItem('refreshToken', tokens.refreshToken)
}

export function clearAuth() {
  accessToken.value = ''
  refreshToken.value = ''
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
}

export function currentRefreshToken() {
  return refreshToken.value || localStorage.getItem('refreshToken')
}
