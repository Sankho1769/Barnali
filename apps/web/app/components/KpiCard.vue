<script setup lang="ts">
defineProps<{ label: string; value: number | null | undefined; hint: string }>()
</script>

<template>
  <section class="kpi-card" :aria-label="`${label}: ${value ?? 'unavailable'}`">
    <span class="kpi-card__label">{{ label }}</span>
    <span v-if="value !== null && value !== undefined" class="kpi-card__value">{{ new Intl.NumberFormat().format(value) }}</span>
    <span v-else class="kpi-card__value" aria-label="Unavailable">—</span>
    <span class="kpi-card__hint">{{ hint }}</span>
  </section>
</template>
