<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getEventDetail, listEventSkus } from '../api/events'
import { createRushRequest, getReservation } from '../api/rush'
import EventArtwork from '../components/EventArtwork.vue'
import { getErrorMessage } from '../api/request'
import { eventStatusText, formatAmount, formatDateTime } from '../utils/format'

const props = defineProps({
  id: {
    type: String,
    required: true
  }
})

const router = useRouter()
const event = ref(null)
const skus = ref([])
const loading = ref(false)
const rushingSkuId = ref(null)
const message = ref('')
const error = ref('')
const reservationId = ref('')
const reservationStatus = ref('')
const reservationOrderId = ref('')
const requestKeys = new Map()
let reservationPollTimer

async function loadDetail() {
  loading.value = true
  error.value = ''
  try {
    const [eventResponse, skuResponse] = await Promise.all([
      getEventDetail(props.id),
      listEventSkus(props.id)
    ])
    event.value = eventResponse.data
    skus.value = skuResponse.data
  } catch (err) {
    error.value = getErrorMessage(err, '演出详情加载失败')
  } finally {
    loading.value = false
  }
}

async function rush(sku) {
  if (!localStorage.getItem('accessToken')) {
    router.push({ path: '/login', query: { redirect: `/events/${props.id}` } })
    return
  }

  message.value = ''
  error.value = ''
  reservationId.value = ''
  reservationStatus.value = ''
  reservationOrderId.value = ''
  rushingSkuId.value = sku.id
  const idempotencyKey = requestKeys.get(sku.id) || createRequestId()
  requestKeys.set(sku.id, idempotencyKey)
  try {
    const { data } = await createRushRequest({
      eventId: props.id,
      skuId: sku.id,
      quantity: 1
    }, idempotencyKey)
    requestKeys.delete(sku.id)
    reservationId.value = data.reservationId
    applyReservationState(data)
    pollReservation(data.reservationId)
  } catch (err) {
    error.value = getErrorMessage(err, '抢票请求未能确认，请重试')
  } finally {
    rushingSkuId.value = null
  }
}

function createRequestId() {
  return globalThis.crypto?.randomUUID?.() || `rush-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function applyReservationState(data) {
  reservationStatus.value = data.status || 'RESERVED'
  reservationOrderId.value = data.orderId || ''
  message.value = {
    RESERVED: '已接受请求，正在为你生成订单',
    QUEUED: '票已预留，正在生成订单',
    ORDER_CREATED: '订单已生成，请前往我的订单完成模拟支付',
    PAID: '这张票已完成模拟支付',
    RELEASE_PENDING: '本次未能生成订单，正在返还库存',
    RELEASED: '预留已结束，库存已经返还',
    REJECTED: '本次请求未通过'
  }[data.status] || '请求状态正在更新'
}

function pollReservation(nextReservationId, attempts = 0) {
  clearTimeout(reservationPollTimer)
  reservationPollTimer = setTimeout(async () => {
    try {
      const { data } = await getReservation(nextReservationId)
      applyReservationState(data)
      if (['ORDER_CREATED', 'PAID', 'RELEASED', 'REJECTED'].includes(data.status)) return
      if (attempts < 9) pollReservation(nextReservationId, attempts + 1)
    } catch {
      message.value = '状态尚未确认，正在重试'
      if (attempts < 9) pollReservation(nextReservationId, attempts + 1)
    }
  }, 1000)
}

function skuAvailability(sku) {
  const now = Date.now()
  const startsAt = sku.saleStartTime ? new Date(sku.saleStartTime).getTime() : null
  const endsAt = sku.saleEndTime ? new Date(sku.saleEndTime).getTime() : null

  if (!sku.stockInitialized || sku.stock === null || sku.stock === undefined) {
    return { available: false, label: '库存准备中', detail: '暂时无法提交，请稍后刷新' }
  }
  if (sku.status === 2 || Number(sku.stock) <= 0) {
    return { available: false, label: '已售罄', detail: '本票档暂无可用库存' }
  }
  if (sku.status === 0 || (startsAt && startsAt > now)) {
    return { available: false, label: '尚未开售', detail: `${formatDateTime(sku.saleStartTime)} 开售` }
  }
  if (endsAt && endsAt < now) {
    return { available: false, label: '售票已结束', detail: '本票档已停止售卖' }
  }
  return {
    available: true,
    label: '立即抢票',
    detail: `实时余票 ${sku.stock} · 每人限购 ${sku.limitPerUser} 张`
  }
}

onMounted(loadDetail)
onBeforeUnmount(() => clearTimeout(reservationPollTimer))
</script>

<template>
  <div class="detail-page">
    <RouterLink class="back-link" to="/events"><span aria-hidden="true">←</span> 返回演出</RouterLink>

    <p v-if="error && !event" class="notice error" role="alert">{{ error }}</p>

    <section v-if="loading" class="detail-loading" aria-label="正在加载演出详情">
      <div class="skeleton skeleton-detail-art"></div>
      <div class="detail-loading-copy">
        <span class="skeleton skeleton-line short"></span>
        <span class="skeleton skeleton-line display"></span>
        <span class="skeleton skeleton-line"></span>
        <span class="skeleton skeleton-line medium"></span>
      </div>
    </section>

    <template v-else-if="event">
      <section class="detail-hero">
        <EventArtwork :event="event" variant="detail" />
        <div class="detail-copy">
          <div class="card-tags">
            <span v-if="event.isHot === 1" class="tag hot"><i></i> 热门</span>
            <span class="tag">{{ eventStatusText(event.status) }}</span>
          </div>
          <p class="detail-artist">{{ event.artist }}</p>
          <h1>{{ event.title }}</h1>
          <div class="detail-facts">
            <div>
              <span>演出时间</span>
              <strong>{{ formatDateTime(event.eventTime) }}</strong>
            </div>
            <div>
              <span>演出地点</span>
              <strong>{{ event.city }} · {{ event.venue }}</strong>
            </div>
          </div>
        </div>
      </section>

      <section class="detail-layout">
        <article class="panel event-about">
          <p class="eyebrow">ABOUT THE SHOW</p>
          <h2>关于这场演出</h2>
          <p class="description">{{ event.description || '演出详情正在完善中。' }}</p>

          <div class="trust-note">
            <span class="trust-icon" aria-hidden="true">✓</span>
            <div>
              <strong>每一步都有明确结果</strong>
              <p>请求接受后会持续查询预留状态，只有订单实际生成后才会提示成功。</p>
            </div>
          </div>
        </article>

        <aside class="panel sku-panel">
          <div class="section-title sku-title">
            <div>
              <p class="eyebrow">SELECT TICKET</p>
              <h2>选择票档</h2>
            </div>
            <button class="refresh-button" type="button" :disabled="loading" @click="loadDetail">刷新</button>
          </div>

          <p class="sku-hint">实时余票可能随抢票请求变化，每位用户每个票档限购 1 张。</p>
          <p v-if="error" class="notice error" role="alert">{{ error }}</p>
          <p v-if="skus.length === 0" class="empty compact">暂无票档</p>

          <div class="sku-list">
            <button
              v-for="sku in skus"
              :key="sku.id"
              class="sku-option"
              type="button"
              :disabled="!skuAvailability(sku).available || rushingSkuId === sku.id"
              @click="rush(sku)"
            >
              <span class="sku-copy">
                <strong>{{ sku.name }}</strong>
                <small>{{ skuAvailability(sku).detail }}</small>
              </span>
              <span class="sku-action">
                <strong>{{ formatAmount(sku.price) }}</strong>
                <small>{{ rushingSkuId === sku.id ? '提交中…' : skuAvailability(sku).label }}</small>
              </span>
            </button>
          </div>

          <Transition name="sheet">
            <div v-if="message" class="reservation-sheet" :class="`status-${reservationStatus.toLowerCase()}`" aria-live="polite">
              <div class="reservation-status-icon" aria-hidden="true">
                <span v-if="['ORDER_CREATED', 'PAID'].includes(reservationStatus)">✓</span>
                <span v-else-if="['RELEASED', 'REJECTED'].includes(reservationStatus)">×</span>
                <span v-else class="status-spinner"></span>
              </div>
              <div>
                <small>抢票状态</small>
                <strong>{{ message }}</strong>
                <details v-if="reservationId">
                  <summary>查看预留编号</summary>
                  <code>{{ reservationId }}</code>
                </details>
              </div>
              <RouterLink v-if="reservationOrderId" class="secondary-button compact-button" to="/orders">去订单</RouterLink>
            </div>
          </Transition>

          <p class="ai-boundary-note">
            <span aria-hidden="true">✦</span>
            只读助手不会代你抢票；提交动作始终由你亲自确认。
          </p>
        </aside>
      </section>
    </template>
  </div>
</template>
