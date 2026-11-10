<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
import type { DiscussionDetail } from '~/types/api'

const route = useRoute()
const api = useApi()
const auth = useAuth()
const reply = ref('')
const replyError = ref('')
const replying = ref(false)
const actionMessage = ref('')
const actionError = ref('')
const reportOpen = ref(false)
const reportReason = ref('OTHER')
const reportDetails = ref('')
const actionBusy = ref(false)
const slug = computed(() => String(route.params.slug))
const { data: discussion, pending, error, refresh } = await useAsyncData(() => `discussion-${slug.value}`, () => api.request<DiscussionDetail>(`/public/discussions/${encodeURIComponent(slug.value)}`), { watch: [slug] })
useSeoMeta({
  title: () => `${discussion.value?.title || 'Discussion'} · BARNALI`,
  description: () => discussion.value?.excerpt || 'Read and join a public BARNALI discussion.',
  ogTitle: () => discussion.value?.title || 'BARNALI discussion',
  ogDescription: () => discussion.value?.excerpt || 'A thoughtful community discussion.',
  robots: 'index, follow'
})

async function postReply() {
  if (!discussion.value || !reply.value.trim()) return
  replying.value = true
  replyError.value = ''
  try {
    await api.request(`/discussions/${encodeURIComponent(discussion.value.slug)}/replies`, { method: 'POST', body: { body: reply.value.trim() } })
    reply.value = ''
    await refresh()
  } catch (cause: unknown) {
    replyError.value = getRequestError(cause, 'Your reply could not be posted. Please try again.')
  } finally {
    replying.value = false
  }
}

async function vote(value: number) {
  if (!discussion.value) return
  actionBusy.value = true; actionError.value = ''; actionMessage.value = ''
  try {
    await api.request(`/discussions/${encodeURIComponent(discussion.value.slug)}/vote`, { method: 'POST', body: { value } })
    actionMessage.value = 'Your vote was recorded.'
    await refresh()
  } catch (cause: unknown) { actionError.value = getRequestError(cause, 'Your vote could not be recorded.') }
  finally { actionBusy.value = false }
}

async function submitReport() {
  if (!discussion.value) return
  actionBusy.value = true; actionError.value = ''; actionMessage.value = ''
  try {
    await api.request('/reports', { method: 'POST', body: { targetType: 'DISCUSSION', targetId: discussion.value.id, reason: reportReason.value, details: reportDetails.value } })
    reportOpen.value = false; reportDetails.value = ''; actionMessage.value = 'Thanks. Your report was sent to the moderation team.'
  } catch (cause: unknown) { actionError.value = getRequestError(cause, 'Your report could not be submitted.') }
  finally { actionBusy.value = false }
}
</script>

<template>
  <div v-if="pending" class="surface surface-pad" role="status">Loading discussion…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">This discussion is unavailable or may have been removed.</div>
  <template v-else-if="discussion">
    <article class="surface discussion-detail">
      <NuxtLink class="eyebrow" :to="`/rooms/${discussion.roomSlug}`">{{ discussion.roomName }}</NuxtLink>
      <h1 class="serif-title">{{ discussion.title }}</h1>
      <div class="discussion-card__meta"><span class="avatar" aria-hidden="true">{{ discussion.authorName.slice(0, 1).toUpperCase() }}</span><span><strong>{{ discussion.authorName }}</strong><span class="muted"> · {{ new Date(discussion.createdAt).toLocaleString() }}</span></span></div>
      <p class="discussion-detail__body">{{ discussion.body }}</p>
      <div class="discussion-card__footer"><span>{{ discussion.voteCount }} votes</span><span>{{ discussion.replies.length }} replies</span></div>
      <div v-if="auth.user.value" class="discussion-detail__actions">
        <button class="btn btn--secondary btn--small" type="button" :disabled="actionBusy" @click="vote(1)">Helpful</button>
        <button class="btn btn--outline btn--small" type="button" :disabled="actionBusy" @click="reportOpen = !reportOpen">Report</button>
      </div>
      <div v-if="actionMessage" class="notice notice--success" role="status">{{ actionMessage }}</div>
      <div v-if="actionError" class="notice notice--error" role="alert">{{ actionError }}</div>
      <form v-if="reportOpen" class="reply-form" @submit.prevent="submitReport">
        <label for="report-reason">Why does this need review?</label>
        <select id="report-reason" v-model="reportReason" class="select"><option value="SPAM">Spam</option><option value="HARASSMENT">Harassment</option><option value="MISINFORMATION">Potential misinformation</option><option value="OFF_TOPIC">Off topic</option><option value="OTHER">Other</option></select>
        <label for="report-details">Details (optional)</label><textarea id="report-details" v-model="reportDetails" class="compose__input" maxlength="1000" />
        <button class="btn btn--danger" type="submit" :disabled="actionBusy">{{ actionBusy ? 'Sending…' : 'Send report' }}</button>
      </form>
    </article>
    <section id="replies" class="surface surface-pad" aria-labelledby="reply-heading">
      <h2 id="reply-heading" class="serif-title" style="font-size:1.35rem">Replies</h2>
      <form v-if="auth.user.value" class="reply-form" @submit.prevent="postReply">
        <label for="reply-body">Add a thoughtful reply</label><textarea id="reply-body" v-model="reply" class="compose__input" maxlength="8000" required />
        <p v-if="replyError" class="notice notice--error" role="alert">{{ replyError }}</p>
        <button class="btn" type="submit" :disabled="replying || !reply.trim()">{{ replying ? 'Posting…' : 'Post reply' }}</button>
      </form>
      <p v-else class="muted">Sign in to join the conversation. <NuxtLink to="/login">Sign in</NuxtLink></p>
      <EmptyState v-if="!discussion.replies.length" title="No replies yet" description="Be the first to share your perspective." />
      <article v-for="item in discussion.replies" :key="item.id" class="reply-item">
        <div class="discussion-card__meta"><span class="avatar" aria-hidden="true">{{ item.authorName.slice(0, 1).toUpperCase() }}</span><span><strong>{{ item.authorName }}</strong><span class="muted"> · {{ new Date(item.createdAt).toLocaleString() }}</span></span></div>
        <p>{{ item.body }}</p>
      </article>
    </section>
  </template>
</template>

<style scoped>
.discussion-detail { display: grid; gap: 14px; padding: clamp(18px,3vw,30px); }
.discussion-detail h1 { font-size: clamp(1.7rem,4vw,2.5rem); line-height: 1.12; }
.discussion-detail__body { white-space: pre-wrap; line-height: 1.7; font-size: 1.02rem; }
.discussion-detail__actions { display:flex; flex-wrap:wrap; gap:8px; }
.reply-form { display: grid; gap: 10px; margin: 18px 0; }
.reply-form label { font-weight: 700; }
.reply-form .btn { justify-self: start; }
.reply-item { padding: 18px 0; border-top: 1px solid var(--b-border); }
.reply-item p { margin: 10px 0 0 48px; line-height: 1.6; white-space: pre-wrap; }
</style>
