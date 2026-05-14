import request from './request'

export function listMyOrders() {
  return request.get('/orders/me')
}

export function payOrder(orderId) {
  return request.post(`/orders/${orderId}/pay`)
}

export function cancelOrder(orderId) {
  return request.post(`/orders/${orderId}/cancel`)
}
