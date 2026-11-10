<script setup lang="ts">
import type { TimePoint } from '~/types/api'

const props = defineProps<{ title: string; description: string; points: TimePoint[]; unavailable?: boolean }>()
const width = 560
const height = 190
const pad = { top: 14, right: 12, bottom: 26, left: 32 }
const maxValue = computed(() => Math.max(1, ...props.points.map(point => point.value)))
const coordinates = computed(() => props.points.map((point, index) => ({
  ...point,
  x: pad.left + (props.points.length <= 1 ? 0 : index * (width - pad.left - pad.right) / (props.points.length - 1)),
  y: pad.top + (height - pad.top - pad.bottom) * (1 - point.value / maxValue.value)
})))
const path = computed(() => coordinates.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const dateLabel = (value: string) => new Date(`${value}T00:00:00Z`).toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' })
const firstLabel = computed(() => props.points[0] ? dateLabel(props.points[0].date) : '')
const lastLabel = computed(() => props.points.at(-1) ? dateLabel(props.points.at(-1)!.date) : '')
</script>

<template>
  <section class="chart-card" :aria-label="title">
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
    <div v-if="unavailable" class="notice" role="status">Analytics are unavailable until the API is connected.</div>
    <div v-else-if="!points.length" class="empty-state" role="status"><p>No activity was recorded in this period.</p></div>
    <template v-else>
      <svg class="chart-svg" viewBox="0 0 560 190" role="img" :aria-label="`${title} chart. ${points.length} daily data points.`" preserveAspectRatio="none">
        <line x1="32" y1="160" x2="548" y2="160" stroke="#dcded8" />
        <line x1="32" y1="14" x2="32" y2="160" stroke="#dcded8" />
        <path :d="path" fill="none" stroke="#007780" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
        <circle v-for="point in coordinates" :key="point.date" :cx="point.x" :cy="point.y" r="3.5" fill="#073B2A">
          <title>{{ dateLabel(point.date) }}: {{ point.value }}</title>
        </circle>
        <text x="32" y="183">{{ firstLabel }}</text>
        <text x="548" y="183" text-anchor="end">{{ lastLabel }}</text>
      </svg>
      <details class="chart-data">
        <summary>View chart data</summary>
        <ul><li v-for="point in points" :key="point.date">{{ dateLabel(point.date) }}: {{ point.value }}</li></ul>
      </details>
    </template>
  </section>
</template>
