import request from './request'

export function sendSmsCode(phone) {
  return request.post('/auth/sms-code', { phone })
}

export function login(phone, code) {
  return request.post('/auth/login', { phone, code })
}

export function logout(refreshToken) {
  return request.post('/auth/logout', { refreshToken })
}
