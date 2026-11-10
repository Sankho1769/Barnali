<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
import type { DiscussionSummary, PageResult, RoomSummary } from '~/types/api'

useSeoMeta({
  title: 'BARNALI — Ideas, people, perspectives',
  description: 'Ask questions, share experiences and learn from a community of curious minds.',
  ogTitle: 'BARNALI — A community for curious minds',
  ogDescription: 'Thoughtful discussions and welcoming rooms for curious people.'
})

const api = useApi()
const auth = useAuth()
const draft = ref('')
const title = ref('')
const roomId = ref('')
const composeError = ref('')
const posting = ref(false)
const { data: feed, pending, error, refresh } = await useAsyncData('home-discussions', () =>
  api.request<PageResult<DiscussionSummary>>('/public/discussions?page=0&size=12&sort=latest')
)
const { data: myRooms, refresh: refreshRooms } = await useAsyncData('my-discussion-rooms', () =>
  auth.user.value ? api.request<RoomSummary[]>('/community/me/rooms') : Promise.resolve([])
)
watch(() => auth.user.value?.id, () => { void refreshRooms() })
async function retryFeed() { await refresh() }
async function publish() {
  if (!draft.value.trim() || !title.value.trim() || !roomId.value) return
  posting.value = true
  composeError.value = ''
  try {
    await api.request('/discussions', { method: 'POST', body: { title: title.value.trim(), body: draft.value.trim(), roomId: roomId.value } })
    title.value = ''
    draft.value = ''
    await refresh()
  } catch (cause: unknown) {
    composeError.value = getRequestError(cause, 'Your discussion could not be published. Please try again.')
  } finally {
    posting.value = false
  }
}
</script>

<template>
  <section class="surface compose" aria-label="Start a discussion">
    <div class="compose__top">
      <span class="avatar" aria-hidden="true">{{ auth.user.value?.displayName.slice(0, 1).toUpperCase() || '?' }}</span>
      <div class="compose__fields">
        <label class="sr-only" for="post-title">Discussion title</label>
        <input id="post-title" v-model="title" class="input" :disabled="!auth.user.value" maxlength="180" placeholder="Discussion title">
        <label class="sr-only" for="post-body">Discussion body</label>
        <textarea id="post-body" v-model="draft" class="compose__input" :disabled="!auth.user.value" maxlength="12000" :placeholder="auth.user.value ? 'Share your thoughts, ask a question, or start a discussion…' : 'Sign in to share a question or idea with the community.'" />
      </div>
    </div>
    <p v-if="composeError" class="notice notice--error" role="alert">{{ composeError }}</p>
    <div class="compose__footer">
      <span v-if="!auth.user.value" class="compose__hint">Read public discussions without an account.</span>
      <div v-else class="compose__footer-fields">
        <label class="sr-only" for="post-room">Choose one of your rooms</label>
        <select id="post-room" v-model="roomId" class="select" :disabled="!myRooms?.length">
          <option value="">{{ myRooms?.length ? 'Choose a room' : 'Join a room before posting' }}</option>
          <option v-for="room in myRooms" :key="room.id" :value="room.id">{{ room.name }}</option>
        </select>
        <NuxtLink v-if="!myRooms?.length" to="/rooms" class="compose__hint">Browse rooms</NuxtLink>
      </div>
      <NuxtLink v-if="!auth.user.value" class="btn btn--secondary" to="/login">Sign in to post</NuxtLink>
      <button v-else class="btn" type="button" :disabled="posting || !draft.trim() || !title.trim() || !roomId" @click="publish">{{ posting ? 'Publishing…' : 'Publish discussion' }}</button>
    </div>
  </section>

  <nav class="surface feed-tabs" aria-label="Discussion sort">
    <NuxtLink to="/" aria-current="page">Latest<span class="feed-tab__accent" aria-hidden="true" /></NuxtLink>
    <NuxtLink to="/discussions?sort=popular">Popular</NuxtLink>
  </nav>

  <div v-if="pending" class="surface surface-pad" role="status">Loading discussions…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">
    <p>The community feed is unavailable right now.</p>
    <button class="btn btn--outline btn--small" type="button" @click="retryFeed">Try again</button>
  </div>
  <EmptyState v-else-if="!feed?.items.length" title="Start the first conversation" description="There are no published discussions yet. Sign in to ask a question or share an idea." action-label="Create account" action-to="/register" />
  <template v-else>
    <DiscussionCard v-for="discussion in feed.items" :key="discussion.id" :discussion="discussion" />
  </template>

</template>

<style scoped>
.compose__fields { display:grid; gap:9px; min-width:0; }
.compose__footer-fields { display:flex; align-items:center; gap:10px; min-width:0; }
.notice p { margin: 0 0 9px; }
@media(max-width:600px) { .compose__footer-fields { align-items:stretch; flex-direction:column; } }
</style>
