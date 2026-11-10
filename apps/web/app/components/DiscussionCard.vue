<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
import type { DiscussionSummary } from '~/types/api'

const { discussion } = defineProps<{ discussion: DiscussionSummary }>()

const api = useApi()
const auth = useAuth()
const route = useRoute()
const actionMessage = ref('')
const actionError = ref('')
const actionBusy = ref(false)
const reportOpen = ref(false)
const reportReason = ref('OTHER')
const reportDetails = ref('')
const confirmedVote = ref<number | null>(null)

function relativeDate(value: string) {
  const date = new Date(value).getTime()
  if (!Number.isFinite(date)) return 'date unavailable'
  const seconds = Math.max(1, Math.floor((Date.now() - date) / 1000))
  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  return `${Math.floor(seconds / 86400)}d ago`
}

async function vote(value: number) {
  actionMessage.value = ''
  actionError.value = ''
  actionBusy.value = true
  try {
    await api.request(`/discussions/${encodeURIComponent(discussion.slug)}/vote`, { method: 'POST', body: { value } })
    confirmedVote.value = value
    actionMessage.value = 'Your vote was recorded.'
    try {
      await refreshNuxtData()
    } catch {
      actionMessage.value = 'Your vote was recorded. Reload the page to see the updated total.'
    }
  } catch (cause: unknown) {
    actionError.value = getRequestError(cause, 'Your vote could not be recorded. Please try again.')
  } finally {
    actionBusy.value = false
  }
}

async function submitReport() {
  actionMessage.value = ''
  actionError.value = ''
  actionBusy.value = true
  try {
    await api.request('/reports', {
      method: 'POST',
      body: { targetType: 'DISCUSSION', targetId: discussion.id, reason: reportReason.value, details: reportDetails.value }
    })
    reportOpen.value = false
    reportDetails.value = ''
    actionMessage.value = 'Thanks. Your report was sent to the moderation team.'
  } catch (cause: unknown) {
    actionError.value = getRequestError(cause, 'Your report could not be submitted. Please try again.')
  } finally {
    actionBusy.value = false
  }
}

async function shareDiscussion() {
  actionMessage.value = ''
  actionError.value = ''
  const url = `${window.location.origin}/discussions/${encodeURIComponent(discussion.slug)}`
  try {
    if (navigator.share) {
      await navigator.share({ title: discussion.title, url })
      actionMessage.value = 'Sharing completed.'
    } else if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
      actionMessage.value = 'Discussion link copied.'
    } else {
      actionError.value = 'Sharing is not available in this browser.'
    }
  } catch (cause: unknown) {
    if (cause instanceof DOMException && cause.name === 'AbortError') return
    actionError.value = 'The discussion link could not be shared. Please try again.'
  }
}
</script>

<template>
  <article class="surface discussion-card">
    <div class="discussion-card__meta">
      <span class="avatar" aria-hidden="true">{{ discussion.authorName.slice(0, 1).toUpperCase() }}</span>
      <span class="discussion-card__byline"><strong>{{ discussion.authorName }}</strong><span class="muted"> · {{ relativeDate(discussion.createdAt) }}</span></span>
    </div>
    <h2 class="discussion-card__title">
      <NuxtLink :to="`/discussions/${discussion.slug}`">{{ discussion.title }}</NuxtLink>
    </h2>
    <p class="discussion-card__excerpt">{{ discussion.excerpt }}</p>
    <footer class="discussion-card__footer">
      <div class="discussion-card__actions" aria-label="Discussion actions">
        <template v-if="auth.user.value">
          <button class="discussion-action" :class="{ 'discussion-action--upvoted': confirmedVote === 1 }" type="button" :disabled="actionBusy" :aria-pressed="confirmedVote === 1" aria-label="Upvote discussion" @click="vote(1)">
            <span class="discussion-action__arrow" aria-hidden="true">↑</span><span>{{ discussion.voteCount }}</span>
          </button>
          <button class="discussion-action" :class="{ 'discussion-action--downvoted': confirmedVote === -1 }" type="button" :disabled="actionBusy" :aria-pressed="confirmedVote === -1" aria-label="Downvote discussion" @click="vote(-1)">
            <span class="discussion-action__arrow" aria-hidden="true">↓</span><span class="sr-only">Downvote</span>
          </button>
        </template>
        <NuxtLink v-else class="discussion-action" :to="{ path: '/login', query: { next: route.fullPath } }" aria-label="Sign in to vote on this discussion">
          <span class="discussion-action__arrow" aria-hidden="true">↑</span><span>{{ discussion.voteCount }}</span><span class="sr-only">Sign in to vote</span>
        </NuxtLink>
        <NuxtLink class="discussion-action" :to="{ path: `/discussions/${discussion.slug}`, hash: '#replies' }" :aria-label="`${discussion.replyCount} replies`">
          <IconGlyph name="message-circle" :size="19" /><span>{{ discussion.replyCount }}</span>
        </NuxtLink>
        <button class="discussion-action" type="button" aria-label="Share discussion" @click="shareDiscussion">
          <IconGlyph name="link" :size="18" /><span class="discussion-action__label">Share</span>
        </button>
        <details class="discussion-more">
          <summary aria-label="More discussion actions">More</summary>
          <div class="discussion-more__menu">
            <template v-if="auth.user.value">
              <button type="button" @click="reportOpen = !reportOpen">{{ reportOpen ? 'Cancel report' : 'Report discussion' }}</button>
            </template>
            <NuxtLink v-else :to="{ path: '/login', query: { next: route.fullPath } }">Sign in to report</NuxtLink>
          </div>
        </details>
        <NuxtLink class="discussion-card__room" :to="`/rooms/${discussion.roomSlug}`"><span aria-hidden="true" class="discussion-card__room-dot" />{{ discussion.roomName }}</NuxtLink>
      </div>
    </footer>
    <form v-if="reportOpen && auth.user.value" class="discussion-report" @submit.prevent="submitReport">
      <label :for="`report-reason-${discussion.id}`">Why does this need review?</label>
      <select :id="`report-reason-${discussion.id}`" v-model="reportReason" class="select">
        <option value="SPAM">Spam</option><option value="HARASSMENT">Harassment</option><option value="MISINFORMATION">Potential misinformation</option><option value="OFF_TOPIC">Off topic</option><option value="OTHER">Other</option>
      </select>
      <label :for="`report-details-${discussion.id}`">Details (optional)</label>
      <textarea :id="`report-details-${discussion.id}`" v-model="reportDetails" class="compose__input" maxlength="1000" />
      <button class="btn btn--danger btn--small" type="submit" :disabled="actionBusy">{{ actionBusy ? 'Sending…' : 'Send report' }}</button>
    </form>
    <p v-if="actionMessage" class="discussion-feedback notice notice--success" role="status">{{ actionMessage }}</p>
    <p v-if="actionError" class="discussion-feedback notice notice--error" role="alert">{{ actionError }}</p>
  </article>
</template>
