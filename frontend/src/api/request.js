import axios from 'axios'
import { clearAuth, saveAuth } from '../stores/auth'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => response,
  async (error) => {
    const status = error.response?.status
    const refreshToken = localStorage.getItem('refreshToken')

    if (status === 401 && refreshToken && !error.config._retry) {
      error.config._retry = true
      try {
        const { data } = await axios.post('/api/auth/refresh', { refreshToken })
        saveAuth(data)
        error.config.headers.Authorization = `${data.tokenType || 'Bearer'} ${data.accessToken}`
        return request(error.config)
      } catch {
        clearAuth()
      }
    }

    return Promise.reject(error)
  }
)

export function getErrorMessage(error, fallback = '请求失败') {
  return error.response?.data?.message || error.message || fallback
}

export default request
