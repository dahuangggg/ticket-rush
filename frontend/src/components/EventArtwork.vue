<script setup>
import { computed } from 'vue'

const props = defineProps({
  event: {
    type: Object,
    required: true
  },
  variant: {
    type: String,
    default: 'card'
  }
})

const theme = computed(() => `theme-${(Number(props.event.id) || 0) % 4}`)
const artistMark = computed(() => (props.event.artist || props.event.title || 'R').trim().slice(0, 1))
const dateParts = computed(() => {
  if (!props.event.eventTime) return { month: '--', day: '--' }
  const date = new Date(props.event.eventTime)
  if (Number.isNaN(date.getTime())) return { month: '--', day: '--' }
  return {
    month: String(date.getMonth() + 1).padStart(2, '0'),
    day: String(date.getDate()).padStart(2, '0')
  }
})
</script>

<template>
  <div
    class="event-artwork"
    :class="[theme, `event-artwork-${variant}`]"
    :role="event.coverUrl ? undefined : 'img'"
    :aria-label="event.coverUrl ? undefined : `${event.artist || event.title} 演出视觉`"
  >
    <img v-if="event.coverUrl" :src="event.coverUrl" :alt="event.title" />
    <div v-else class="generated-art" aria-hidden="true">
      <span class="art-glow art-glow-primary"></span>
      <span class="art-glow art-glow-secondary"></span>
      <span class="art-orbit"></span>
      <span class="art-kicker">RUSH / LIVE</span>
      <strong class="art-mark">{{ artistMark }}</strong>
      <div class="art-meta">
        <span>{{ event.city || 'LIVE' }}</span>
        <span>{{ dateParts.month }}.{{ dateParts.day }}</span>
      </div>
    </div>
    <div class="art-sheen" aria-hidden="true"></div>
  </div>
</template>
