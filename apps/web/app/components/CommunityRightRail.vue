<script setup lang="ts">
import type { RoomSummary, TrendingNewsResponse } from '~/types/api'

const api = useApi()
const newsRequest = useAsyncData('trending-news', () => api.request<TrendingNewsResponse>('/public/news/trending'))
const roomsRequest = useAsyncData('rail-rooms', () => api.request<RoomSummary[]>('/public/rooms?limit=5'))
const [newsResult, roomsResult] = await Promise.all([newsRequest, roomsRequest])
const { data: news, pending: newsPending, error: newsError, refresh: refreshNews } = newsResult
const { data: rooms, pending: roomsPending, error: roomsError, refresh: refreshRooms } = roomsResult

async function retryRooms() {
  await refreshRooms()
}
</script>

<template>
  <div class="right-rail__stack">
    <TrendingNewsPanel
      :state="newsPending ? 'loading' : newsError ? 'error' : news?.status === 'AVAILABLE' ? 'available' : 'unavailable'"
      :items="news?.items || []"
      @retry="refreshNews"
    />
    <section class="surface rail-card">
      <h2>Popular rooms</h2>
      <p v-if="roomsPending" class="muted" role="status">Loading public rooms…</p>
      <div v-else-if="roomsError" class="rail-state" role="alert">
        <p class="muted">Room information is unavailable right now.</p>
        <button class="btn btn--outline btn--small" type="button" @click="retryRooms">Try again</button>
      </div>
      <div v-else-if="rooms?.length" class="rail-link-list">
        <NuxtLink v-for="room in rooms" :key="room.id" class="rail-link" :to="`/rooms/${room.slug}`">
          <strong>{{ room.name }}</strong><span>{{ room.memberCount }} members · {{ room.discussionCount }} discussions</span>
        </NuxtLink>
      </div>
      <p v-else class="muted" role="status">No public rooms are available yet.</p>
      <NuxtLink to="/rooms">Explore all rooms →</NuxtLink>
    </section>
    <section class="surface rail-card">
      <h2>A safer community</h2>
      <p class="muted">Keep discussions curious, kind, and grounded in real experience. You can report a post that needs review.</p>
      <NuxtLink to="/community-guidelines">Community guidelines</NuxtLink>
    </section>
  </div>
</template>

<style scoped>
.right-rail__stack { display: grid; gap: 16px; }
.rail-state { display: grid; justify-items: start; gap: 9px; font-size: .84rem; }
.rail-state p { margin: 0; }
</style>
