<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { listEvents } from '../api/events'
import EventArtwork from '../components/EventArtwork.vue'
import { getErrorMessage } from '../api/request'
import { eventStatusText, formatDateTime } from '../utils/format'

const events = ref([])
const loading = ref(false)
const error = ref('')
const filters = reactive({
  city: '',
  keyword: '',
  date: ''
})

const onSaleCount = computed(() => events.value.filter((event) => event.status === 1).length)
const hasFilters = computed(() => Object.values(filters).some(Boolean))

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
  <section class="event-hero">
    <div class="hero-copy">
      <p class="eyebrow"><span class="live-dot"></span> RUSH LIVE / 现场精选</p>
      <h1>把下一场现场，<br /><span>握在手里。</span></h1>
      <p class="hero-lede">
        清楚地看见开售时间、实时票档与每一步结果。少一点焦虑，多一点抵达现场的确定感。
      </p>
      <div class="hero-actions">
        <a class="primary-button" href="#discover">浏览演出 <span aria-hidden="true">↓</span></a>
        <RouterLink class="secondary-button" to="/ai">问票务助手</RouterLink>
      </div>
    </div>

    <div class="signal-card" aria-label="当前售票概览">
      <div class="signal-card-head">
        <span><i class="live-dot"></i> LIVE STATUS</span>
        <small>实时更新</small>
      </div>
      <strong>{{ String(onSaleCount).padStart(2, '0') }}</strong>
      <p>场演出正在售票</p>
      <div class="signal-wave" aria-hidden="true">
        <i v-for="index in 18" :key="index"></i>
      </div>
      <div class="signal-foot">
        <span>库存以 Redis 实时状态为准</span>
        <span class="signal-arrow" aria-hidden="true">↗</span>
      </div>
    </div>
  </section>

  <section id="discover" class="discover-section">
    <div class="section-title page-head">
      <div>
        <p class="eyebrow">DISCOVER</p>
        <h2>值得奔赴的现场</h2>
        <p class="section-lede">按城市、演出标题或日期，找到你的下一张票。</p>
      </div>
      <RouterLink class="quiet-link" to="/orders">查看我的订单 <span aria-hidden="true">→</span></RouterLink>
    </div>

    <form class="discovery-bar panel" @submit.prevent="loadEvents">
      <label class="field-group field-search">
        <span>演出标题</span>
        <input v-model.trim="filters.keyword" placeholder="输入演出关键词" />
      </label>
      <label class="field-group">
        <span>城市</span>
        <input v-model.trim="filters.city" placeholder="全部城市" />
      </label>
      <label class="field-group">
        <span>演出日期</span>
        <input v-model="filters.date" type="date" />
      </label>
      <div class="filter-actions">
        <button class="primary-button" type="submit" :disabled="loading">
          {{ loading ? '查找中' : '查找演出' }}
        </button>
        <button v-if="hasFilters" class="icon-button" type="button" aria-label="清空筛选" @click="clearFilters">×</button>
      </div>
    </form>

    <p v-if="error" class="notice error" role="alert">{{ error }}</p>

    <section v-if="loading" class="event-grid" aria-label="正在加载演出">
      <article v-for="index in 2" :key="index" class="event-card skeleton-card">
        <div class="skeleton skeleton-art"></div>
        <div class="event-card-body">
          <span class="skeleton skeleton-line short"></span>
          <span class="skeleton skeleton-line title"></span>
          <span class="skeleton skeleton-line"></span>
          <span class="skeleton skeleton-line medium"></span>
        </div>
      </article>
    </section>

    <section v-else class="event-grid">
      <RouterLink
        v-for="event in events"
        :key="event.id"
        class="event-card"
        :to="`/events/${event.id}`"
      >
        <EventArtwork :event="event" />
        <div class="event-card-body">
          <div class="card-tags">
            <span v-if="event.isHot === 1" class="tag hot"><i></i> 热门</span>
            <span class="tag">{{ eventStatusText(event.status) }}</span>
          </div>
          <div class="event-card-title">
            <h3>{{ event.title }}</h3>
            <span class="round-arrow" aria-hidden="true">↗</span>
          </div>
          <p class="event-artist">{{ event.artist }}</p>
          <dl class="event-facts">
            <div>
              <dt>时间</dt>
              <dd>{{ formatDateTime(event.eventTime) }}</dd>
            </div>
            <div>
              <dt>地点</dt>
              <dd>{{ event.city }} · {{ event.venue }}</dd>
            </div>
          </dl>
        </div>
      </RouterLink>

      <div v-if="events.length === 0" class="empty panel">
        <span class="empty-symbol" aria-hidden="true">◎</span>
        <h3>暂时没有匹配的演出</h3>
        <p>换一个城市、标题或日期再试试。</p>
        <button v-if="hasFilters" class="secondary-button" type="button" @click="clearFilters">清空筛选</button>
      </div>
    </section>
  </section>
</template>
