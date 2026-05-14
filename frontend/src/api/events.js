import request from './request'

export function listEvents(params) {
  return request.get('/events', { params })
}

export function getEventDetail(eventId) {
  return request.get(`/events/${eventId}`)
}

export function listEventSkus(eventId) {
  return request.get(`/events/${eventId}/skus`)
}
