<script setup lang="ts">
withDefaults(defineProps<{
  compact?: boolean
  dark?: boolean
  tagline?: boolean
}>(), {
  compact: false,
  dark: false,
  tagline: false
})
</script>

<template>
  <NuxtLink class="barnali-brand" :class="{ 'barnali-brand--compact': compact, 'barnali-brand--dark': dark }"
     to="/" aria-label="BARNALI home">
    <span class="barnali-brand__copy">
      <img class="barnali-brand__wordmark" :src="dark ? '/assets/brand/logos/barnali-logo-dark-background.svg' : '/assets/brand/logos/barnali-logo-horizontal.svg'" alt="BARNALI">
      <span v-if="tagline && !compact" class="barnali-brand__tagline">
        A community for curious minds to learn, share and grow together.
      </span>
    </span>
  </NuxtLink>
</template>

<style scoped>
.barnali-brand { display: inline-flex; align-items: center; color: var(--b-forest); text-decoration: none; }
.barnali-brand__copy { display: flex; flex-direction: column; gap: .25rem; }
.barnali-brand__wordmark { display: block; width: 210px; height: auto; }
.barnali-brand__tagline { max-width: 280px; color: var(--b-muted); font-size: .78rem; line-height: 1.4; }
.barnali-brand--compact .barnali-brand__wordmark { width: 166px; }
.barnali-brand--dark { color: var(--b-ivory); }
.barnali-brand--dark .barnali-brand__tagline { color: #D4DDD6; }
</style>
