<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listEvents } from '../api/events'
import { getErrorMessage } from '../api/request'
import { eventStatusText, formatDateTime } from '../utils/format'

const router = useRouter()
const events = ref([])
const loading = ref(false)
const error = ref('')
const filters = reactive({
  city: '',
  keyword: '',
  date: ''
})

async function loadEvents() {
  loading.value = true
  error.value = ''
  try {
    const params = Object.fromEntries(Object.entries(filters).filter(([, value]) => value))
    const { data } = await listEvents(params)
    events.value = data
  } catch (err) {
    error.value = getErrorMessage(err, '演出列表加载失败')
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  filters.city = ''
  filters.keyword = ''
  filters.date = ''
  loadEvents()
}

onMounted(loadEvents)
</script>

<template>
  <section class="page-head">
    <div>
      <p class="eyebrow">演唱会抢票</p>
      <h1>演出列表</h1>
    </div>
    <RouterLink class="primary-button" to="/orders">查看我的订单</RouterLink>
  </section>

  <section class="toolbar panel">
    <input v-model.trim="filters.city" placeholder="城市，例如 上海" @keyup.enter="loadEvents" />
    <input v-model.trim="filters.keyword" placeholder="艺人 / 演出关键词" @keyup.enter="loadEvents" />
    <input v-model="filters.date" type="date" />
    <button class="primary-button" type="button" @click="loadEvents">筛选</button>
    <button class="secondary-button" type="button" @click="clearFilters">清空</button>
  </section>

  <p v-if="error" class="notice error">{{ error }}</p>
  <p v-if="loading" class="notice">加载中...</p>

  <section v-else class="event-grid">
    <article v-for="event in events" :key="event.id" class="event-card" @click="router.push(`/events/${event.id}`)">
      <img :src="event.coverUrl || '/placeholder-cover.svg'" :alt="event.title" />
      <div class="event-card-body">
        <div class="card-tags">
          <span v-if="event.isHot === 1" class="tag hot">热门</span>
          <span class="tag">{{ eventStatusText(event.status) }}</span>
        </div>
        <h2>{{ event.title }}</h2>
        <p>{{ event.artist }} · {{ event.city }}</p>
        <p>{{ event.venue }}</p>
        <p>{{ formatDateTime(event.eventTime) }}</p>
      </div>
    </article>

    <div v-if="events.length === 0" class="empty panel">暂无匹配演出</div>
  </section>
</template>
