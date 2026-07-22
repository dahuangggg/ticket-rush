import request from './request'

export function createRushRequest(payload, idempotencyKey) {
  return request.post('/ticket-rush/requests', payload, {
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function getReservation(reservationId) {
  return request.get(`/ticket-rush/reservations/${reservationId}`)
}
