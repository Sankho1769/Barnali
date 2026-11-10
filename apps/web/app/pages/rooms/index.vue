<script setup lang="ts">
import type { RoomSummary } from '~/types/api'

useSeoMeta({ title: 'Rooms · BARNALI', description: 'Find a room for every interest and conversation.' })
const api = useApi()
const { data: rooms, pending, error } = await useAsyncData('public-rooms', () => api.request<RoomSummary[]>('/public/rooms'))
</script>

<template>
  <header class="surface surface-pad"><span class="eyebrow">Find your people</span><h1 class="serif-title" style="font-size:2rem;margin-top:8px">Community rooms</h1><p class="muted">Join conversations around topics you care about.</p></header>
  <div v-if="pending" class="surface surface-pad" role="status">Loading rooms…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">Rooms are unavailable right now.</div>
  <EmptyState v-else-if="!rooms?.length" title="Rooms are getting ready" description="No public rooms are available yet. Check back as the community grows." />
  <div v-else class="room-grid">
    <NuxtLink v-for="room in rooms" :key="room.id" class="surface room-card" :to="`/rooms/${room.slug}`">
      <img src="/assets/ui/badges/popular-room.svg" alt="" width="34" height="34">
      <h2>{{ room.name }}</h2><p>{{ room.description }}</p><span>{{ room.memberCount }} members · {{ room.discussionCount }} discussions</span>
    </NuxtLink>
  </div>
</template>

<style scoped>
.room-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.room-card { display: grid; justify-items: start; gap: 8px; padding: 18px; color: inherit; text-decoration: none; }
.room-card h2 { margin: 0; font: 700 1.2rem var(--b-font-display); }
.room-card p { margin: 0; color: var(--b-muted); line-height: 1.5; }
.room-card span { color: var(--b-teal-dark); font-size: .82rem; }
@media(max-width:600px) { .room-grid { grid-template-columns: 1fr; } }
</style>
