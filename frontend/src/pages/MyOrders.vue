<script setup>
import { onMounted, ref } from 'vue'
import { cancelOrder, listMyOrders, payOrder } from '../api/orders'
import { getErrorMessage } from '../api/request'
import { formatAmount, formatDateTime, orderStatusText } from '../utils/format'

const orders = ref([])
const loading = ref(false)
const actionOrderId = ref(null)
const error = ref('')
const message = ref('')

async function loadOrders() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await listMyOrders()
    orders.value = data
  } catch (err) {
    error.value = getErrorMessage(err, '订单加载失败')
  } finally {
    loading.value = false
  }
}

async function runAction(orderId, action) {
  actionOrderId.value = orderId
  error.value = ''
  message.value = ''
  try {
    if (action === 'pay') {
      await payOrder(orderId)
      message.value = '支付成功'
    } else {
      await cancelOrder(orderId)
      message.value = '订单已取消'
    }
    await loadOrders()
  } catch (err) {
    error.value = getErrorMessage(err, '操作失败')
  } finally {
    actionOrderId.value = null
  }
}

onMounted(loadOrders)
</script>

<template>
  <section class="page-head">
    <div>
      <p class="eyebrow">个人中心</p>
      <h1>我的订单</h1>
    </div>
    <button class="secondary-button" type="button" @click="loadOrders">刷新</button>
  </section>

  <p v-if="message" class="notice success">{{ message }}</p>
  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="loading" class="notice">加载中...</p>

  <section v-else class="orders">
    <article v-for="order in orders" :key="order.id" class="panel order-card">
      <div>
        <div class="card-tags">
          <span class="tag">{{ orderStatusText(order.status) }}</span>
        </div>
        <h2>{{ order.orderNo }}</h2>
        <p>演出 ID：{{ order.eventId }} · 票档 ID：{{ order.skuId }} · 数量：{{ order.quantity }}</p>
        <p>创建时间：{{ formatDateTime(order.createTime) }}</p>
      </div>
      <div class="order-side">
        <strong>{{ formatAmount(order.totalAmount) }}</strong>
        <div v-if="order.status === 0" class="order-actions">
          <button class="primary-button" type="button" :disabled="actionOrderId === order.id" @click="runAction(order.id, 'pay')">
            支付
          </button>
          <button class="secondary-button" type="button" :disabled="actionOrderId === order.id" @click="runAction(order.id, 'cancel')">
            取消
          </button>
        </div>
      </div>
    </article>

    <div v-if="orders.length === 0" class="empty panel">暂无订单，抢票成功后稍后刷新查看</div>
  </section>
</template>
