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
  <a class="barnali-brand" :class="{ 'barnali-brand--compact': compact, 'barnali-brand--dark': dark }"
     href="/" aria-label="BARNALI home">
    <img
      class="barnali-brand__mark"
      :src="dark ? '/assets/brand/logos/barnali-mark.svg' : '/assets/brand/logos/barnali-mark.svg'"
      alt=""
      width="44"
      height="44"
    />
    <span class="barnali-brand__copy">
      <span class="barnali-brand__wordmark">BARNALI</span>
      <span v-if="tagline && !compact" class="barnali-brand__tagline">
        A community for curious minds to learn, share and grow together.
      </span>
    </span>
  </a>
</template>

<style scoped>
.barnali-brand { display: inline-flex; align-items: center; gap: .65rem; color: var(--b-forest); text-decoration: none; }
.barnali-brand__mark { display: block; flex: none; object-fit: contain; }
.barnali-brand__copy { display: flex; flex-direction: column; gap: .25rem; }
.barnali-brand__wordmark { font: 700 1.65rem/1 var(--b-font-display); letter-spacing: .09em; }
.barnali-brand__tagline { max-width: 280px; color: var(--b-muted); font-size: .78rem; line-height: 1.4; }
.barnali-brand--compact .barnali-brand__mark { width: 34px; height: 34px; }
.barnali-brand--compact .barnali-brand__wordmark { font-size: 1.25rem; }
.barnali-brand--dark { color: var(--b-ivory); }
.barnali-brand--dark .barnali-brand__tagline { color: #D4DDD6; }
</style>
