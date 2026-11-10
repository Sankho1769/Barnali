<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  description: string
  actionLabel?: string
  actionTo?: string
  illustration?: 'empty-discussions' | 'verification-notice'
}>(), { illustration: 'empty-discussions' })
</script>

<template>
  <section class="surface empty-state" role="status">
    <img :src="`/assets/ui/illustrations/${illustration}.svg`" alt="">
    <h2>{{ title }}</h2>
    <p>{{ description }}</p>
    <NuxtLink v-if="actionLabel && actionTo" class="btn btn--secondary" :to="actionTo">{{ actionLabel }}</NuxtLink>
  </section>
</template>
