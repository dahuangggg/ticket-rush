<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getEventDetail, listEventSkus } from '../api/events'
import { createRushRequest } from '../api/rush'
import { getErrorMessage } from '../api/request'
import { eventStatusText, formatAmount, formatDateTime, skuStatusText } from '../utils/format'

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
  rushingSkuId.value = sku.id
  try {
    const { data } = await createRushRequest({
      eventId: props.id,
      skuId: sku.id,
      quantity: 1
    })
    message.value = data.status === 'QUEUED' ? '抢票请求已提交，请稍后查看订单' : `提交结果：${data.status}`
  } catch (err) {
    error.value = getErrorMessage(err, '抢票失败')
  } finally {
    rushingSkuId.value = null
  }
}

onMounted(loadDetail)
</script>

<template>
  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="loading" class="notice">加载中...</p>

  <section v-else-if="event" class="detail-layout">
    <div class="detail-main">
      <img class="detail-cover" :src="event.coverUrl || '/placeholder-cover.svg'" :alt="event.title" />
      <div class="detail-copy">
        <div class="card-tags">
          <span v-if="event.isHot === 1" class="tag hot">热门</span>
          <span class="tag">{{ eventStatusText(event.status) }}</span>
        </div>
        <h1>{{ event.title }}</h1>
        <p class="muted">{{ event.artist }} · {{ event.city }} · {{ event.venue }}</p>
        <p class="strong">{{ formatDateTime(event.eventTime) }}</p>
        <p class="description">{{ event.description }}</p>
      </div>
    </div>

    <aside class="panel sku-panel">
      <div class="section-title">
        <div>
          <p class="eyebrow">票档选择</p>
          <h2>选择票档抢票</h2>
        </div>
        <RouterLink to="/orders">订单</RouterLink>
      </div>

      <p v-if="message" class="notice success">{{ message }}</p>
      <p v-if="skus.length === 0" class="empty">暂无票档</p>

      <div v-for="sku in skus" :key="sku.id" class="sku-row">
        <div>
          <h3>{{ sku.name }}</h3>
          <p>{{ skuStatusText(sku.status) }} · 库存 {{ sku.stock }} · 限购 {{ sku.limitPerUser }}</p>
          <p>开售 {{ formatDateTime(sku.saleStartTime) }}</p>
        </div>
        <div class="sku-action">
          <strong>{{ formatAmount(sku.price) }}</strong>
          <button
            class="primary-button"
            type="button"
            :disabled="sku.status !== 1 || rushingSkuId === sku.id"
            @click="rush(sku)"
          >
            {{ rushingSkuId === sku.id ? '提交中' : '抢票' }}
          </button>
        </div>
      </div>
    </aside>
  </section>
</template>
