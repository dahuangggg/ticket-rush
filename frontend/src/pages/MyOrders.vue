<script setup>
import { computed, onMounted, ref } from 'vue'
import { cancelOrder, listMyOrders, payOrder } from '../api/orders'
import { getErrorMessage } from '../api/request'
import { formatAmount, formatDateTime, orderStatusText } from '../utils/format'

const orders = ref([])
const loading = ref(false)
const actionOrderId = ref(null)
const cancelCandidate = ref(null)
const error = ref('')
const message = ref('')

const pendingCount = computed(() => orders.value.filter((order) => order.status === 0).length)
const completedCount = computed(() => orders.value.filter((order) => order.status === 1).length)

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
      message.value = '模拟支付已完成'
    } else {
      await cancelOrder(orderId)
      message.value = '订单已取消，预留库存将安全返还'
    }
    await loadOrders()
  } catch (err) {
    error.value = getErrorMessage(err, '操作失败')
  } finally {
    actionOrderId.value = null
  }
}

async function confirmCancel() {
  const orderId = cancelCandidate.value?.id
  cancelCandidate.value = null
  if (orderId) await runAction(orderId, 'cancel')
}

onMounted(loadOrders)
</script>

<template>
  <section class="page-head order-page-head">
    <div>
      <p class="eyebrow">MY TICKETS</p>
      <h1>我的订单</h1>
      <p class="section-lede">查看抢票结果，并完成模拟支付或取消订单。</p>
    </div>
    <button class="secondary-button" type="button" :disabled="loading" @click="loadOrders">
      {{ loading ? '刷新中…' : '刷新订单' }}
    </button>
  </section>

  <section class="order-summary" aria-label="订单概览">
    <div>
      <span>全部订单</span>
      <strong>{{ String(orders.length).padStart(2, '0') }}</strong>
    </div>
    <div>
      <span>待模拟支付</span>
      <strong>{{ String(pendingCount).padStart(2, '0') }}</strong>
    </div>
    <div>
      <span>已完成</span>
      <strong>{{ String(completedCount).padStart(2, '0') }}</strong>
    </div>
  </section>

  <p v-if="message" class="notice success" role="status">{{ message }}</p>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>

  <section v-if="loading" class="orders" aria-label="正在加载订单">
    <article v-for="index in 2" :key="index" class="panel order-card skeleton-card">
      <div class="order-main">
        <span class="skeleton skeleton-line short"></span>
        <span class="skeleton skeleton-line title"></span>
        <span class="skeleton skeleton-line medium"></span>
      </div>
      <span class="skeleton skeleton-price"></span>
    </article>
  </section>

  <section v-else class="orders">
    <article v-for="order in orders" :key="order.id" class="panel order-card">
      <div class="order-main">
        <div class="order-meta-row">
          <span class="status-badge" :class="`order-status-${order.status}`">
            <i></i>{{ orderStatusText(order.status) }}
          </span>
          <span>{{ formatDateTime(order.createTime) }}</span>
        </div>
        <h2>订单 {{ order.orderNo }}</h2>
        <div class="order-facts">
          <span>演出 #{{ order.eventId }}</span>
          <span>票档 #{{ order.skuId }}</span>
          <span>{{ order.quantity }} 张</span>
        </div>
      </div>

      <div class="order-side">
        <small>订单金额</small>
        <strong>{{ formatAmount(order.totalAmount) }}</strong>
        <div v-if="order.status === 0" class="order-actions">
          <button
            class="primary-button"
            type="button"
            :disabled="actionOrderId === order.id"
            @click="runAction(order.id, 'pay')"
          >
            模拟支付
          </button>
          <button
            class="text-danger-button"
            type="button"
            :disabled="actionOrderId === order.id"
            @click="cancelCandidate = order"
          >
            取消订单
          </button>
        </div>
      </div>
    </article>

    <div v-if="orders.length === 0" class="empty panel order-empty">
      <span class="empty-symbol" aria-hidden="true">▱</span>
      <h3>还没有订单</h3>
      <p>找到想看的演出，选择票档后亲自发起抢票。</p>
      <RouterLink class="primary-button" to="/events">去看演出</RouterLink>
    </div>
  </section>

  <Transition name="modal">
    <div v-if="cancelCandidate" class="modal-scrim" @click.self="cancelCandidate = null">
      <section class="confirm-sheet" role="dialog" aria-modal="true" aria-labelledby="cancel-title">
        <span class="confirm-icon" aria-hidden="true">!</span>
        <h2 id="cancel-title">取消这笔订单？</h2>
        <p>取消后，本次预留会被释放。你仍可以重新发起抢票。</p>
        <div class="confirm-actions">
          <button class="secondary-button" type="button" @click="cancelCandidate = null">保留订单</button>
          <button class="danger-button" type="button" @click="confirmCancel">确认取消</button>
        </div>
      </section>
    </div>
  </Transition>
</template>
