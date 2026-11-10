<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
</script>

<template>
  <img :src="`/assets/ui/icons/${name}.svg`" :width="size || 20" :height="size || 20" alt="" aria-hidden="true">
</template>
