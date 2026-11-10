<script setup lang="ts">
import type { DiscussionSummary, PageResult, RoomSummary } from '~/types/api'

const api = useApi()
useSeoMeta({
  title: 'Explore BARNALI',
  description: 'Discover popular public conversations and community rooms on BARNALI.',
  ogTitle: 'Explore BARNALI',
  robots: 'index, follow'
})

const { data: discussions, pending: discussionsPending, error: discussionsError } = await useAsyncData(
  'explore-popular-discussions',
  () => api.request<PageResult<DiscussionSummary>>('/public/discussions?page=0&size=8&sort=popular')
)
const { data: rooms, pending: roomsPending, error: roomsError } = await useAsyncData(
  'explore-public-rooms',
  () => api.request<RoomSummary[]>('/public/rooms?limit=6')
)
</script>

<template>
  <header class="surface surface-pad explore-heading">
    <span class="eyebrow">Ideas · People · Perspectives</span>
    <h1 class="serif-title">Explore what the community is talking about</h1>
    <p class="muted">Find a useful conversation or join a room that shares your curiosity.</p>
    <NuxtLink class="btn" to="/discussions">Browse all discussions</NuxtLink>
  </header>

  <section aria-labelledby="explore-rooms-heading">
    <div class="explore-section-heading"><h2 id="explore-rooms-heading" class="serif-title">Community rooms</h2><NuxtLink to="/rooms">See all rooms →</NuxtLink></div>
    <div v-if="roomsPending" class="surface surface-pad" role="status">Loading public rooms…</div>
    <p v-else-if="roomsError" class="notice notice--error" role="alert">Room information is unavailable right now.</p>
    <EmptyState v-else-if="!rooms?.length" title="No public rooms yet" description="Public rooms will appear here when they are available." action-label="Browse rooms" action-to="/rooms" />
    <div v-else class="room-grid">
      <NuxtLink v-for="room in rooms" :key="room.id" class="surface room-card" :to="`/rooms/${room.slug}`">
        <img src="/assets/ui/badges/popular-room.svg" alt="" width="34" height="34">
        <h3>{{ room.name }}</h3><p>{{ room.description }}</p><span>{{ room.memberCount }} members · {{ room.discussionCount }} discussions</span>
      </NuxtLink>
    </div>
  </section>

  <section aria-labelledby="explore-discussions-heading">
    <div class="explore-section-heading"><h2 id="explore-discussions-heading" class="serif-title">Popular discussions</h2><NuxtLink to="/discussions?sort=popular">See all →</NuxtLink></div>
    <div v-if="discussionsPending" class="surface surface-pad" role="status">Loading popular discussions…</div>
    <p v-else-if="discussionsError" class="notice notice--error" role="alert">Popular discussions are unavailable right now.</p>
    <EmptyState v-else-if="!discussions?.items.length" title="The first conversation is waiting" description="There are no public discussions to discover yet." action-label="Start a discussion" action-to="/register" />
    <DiscussionCard v-for="discussion in discussions?.items || []" :key="discussion.id" :discussion="discussion" />
  </section>
</template>

<style scoped>
.explore-heading { display: grid; justify-items: start; gap: 12px; }
.explore-heading .serif-title { max-width: 24ch; margin: 0; font-size: clamp(1.8rem, 4vw, 2.5rem); }
.explore-heading p { margin: 0; }
.explore-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 24px 0 12px; }
.explore-section-heading .serif-title { margin: 0; font-size: 1.35rem; }
.explore-section-heading a { color: var(--b-teal-dark); }
.room-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 14px; }
.room-card { display: grid; justify-items: start; gap: 8px; padding: 18px; color: inherit; text-decoration: none; }
.room-card h3 { margin: 0; font: 700 1.1rem var(--b-font-display); }
.room-card p { margin: 0; color: var(--b-muted); line-height: 1.5; }
.room-card span { color: var(--b-teal-dark); font-size: .82rem; }
@media(max-width:600px) { .room-grid { grid-template-columns: 1fr; } }
</style>
