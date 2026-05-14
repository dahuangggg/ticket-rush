export function formatAmount(cents) {
  if (cents === null || cents === undefined) return '-'
  return `¥${(Number(cents) / 100).toFixed(0)}`
}

export function formatDateTime(value) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

export function eventStatusText(status) {
  return {
    0: '未上架',
    1: '售卖中',
    2: '已结束'
  }[status] || '未知'
}

export function skuStatusText(status) {
  return {
    0: '未开售',
    1: '售卖中',
    2: '售罄'
  }[status] || '未知'
}

export function orderStatusText(status) {
  return {
    0: '待支付',
    1: '已支付',
    2: '已取消',
    3: '已超时'
  }[status] || '未知'
}
