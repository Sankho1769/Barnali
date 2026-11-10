<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
import type { DiscussionSummary, PageResult, RoomSummary } from '~/types/api'

interface RoomPage extends RoomSummary { discussions: PageResult<DiscussionSummary> }
const route = useRoute()
const api = useApi()
const auth = useAuth()
const membershipBusy = ref(false)
const membershipMessage = ref('')
const membershipError = ref('')
const slug = computed(() => String(route.params.slug))
const { data: room, pending, error } = await useAsyncData(() => `room-${slug.value}`, () => api.request<RoomPage>(`/public/rooms/${encodeURIComponent(slug.value)}`), { watch: [slug] })
useSeoMeta({
  title: () => `${room.value?.name || 'Room'} · BARNALI`,
  description: () => room.value?.description || 'Join a BARNALI room and take part in the discussion.'
})
async function joinRoom() {
  if (!room.value) return
  membershipBusy.value = true; membershipMessage.value = ''; membershipError.value = ''
  try { await api.request(`/rooms/${encodeURIComponent(room.value.slug)}/join`, { method: 'POST' }); membershipMessage.value = 'You joined this room.' }
  catch (cause: unknown) { membershipError.value = getRequestError(cause, 'The room could not be joined.') }
  finally { membershipBusy.value = false }
}
</script>

<template>
  <div v-if="pending" class="surface surface-pad" role="status">Loading room…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">This room could not be loaded. It may have been removed or is temporarily unavailable.</div>
  <template v-else-if="room">
    <header class="surface surface-pad"><span class="eyebrow">Community room</span><h1 class="serif-title" style="font-size:2rem;margin-top:8px">{{ room.name }}</h1><p>{{ room.description }}</p><p class="muted">{{ room.memberCount }} members · {{ room.discussionCount }} discussions</p>
      <button v-if="auth.user.value" class="btn" type="button" :disabled="membershipBusy" @click="joinRoom">{{ membershipBusy ? 'Joining…' : 'Join room' }}</button>
      <NuxtLink v-else class="btn" to="/login">Sign in to join</NuxtLink>
      <p v-if="membershipMessage" class="notice notice--success" role="status">{{ membershipMessage }}</p><p v-if="membershipError" class="notice notice--error" role="alert">{{ membershipError }}</p>
    </header>
    <EmptyState v-if="!room.discussions.items.length" title="No discussions in this room yet" description="Be the first to ask a question or share an idea." action-label="Sign in to post" action-to="/login" />
    <DiscussionCard v-for="item in room.discussions.items" :key="item.id" :discussion="item" />
  </template>
</template>
