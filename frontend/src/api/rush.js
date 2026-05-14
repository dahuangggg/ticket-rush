import request from './request'

export function createRushRequest(payload) {
  return request.post('/ticket-rush/requests', payload)
}
