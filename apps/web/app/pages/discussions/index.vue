<script setup lang="ts">
import type { DiscussionSummary, PageResult } from '~/types/api'

const route = useRoute()
const api = useApi()
const search = computed(() => typeof route.query.q === 'string' ? route.query.q : '')
const sort = computed(() => route.query.sort === 'popular' ? 'popular' : 'latest')
useSeoMeta({
  title: () => search.value ? `Search: ${search.value} · BARNALI` : 'Discussions · BARNALI',
  description: 'Browse public BARNALI discussions and questions.',
  ogTitle: 'BARNALI discussions'
})
const { data: result, pending, error, refresh } = await useAsyncData(
  () => `discussions-${search.value}-${sort.value}`,
  () => api.request<PageResult<DiscussionSummary>>(`/public/discussions?page=0&size=30&sort=${sort.value}${search.value ? `&q=${encodeURIComponent(search.value)}` : ''}`),
  { watch: [search, sort] }
)
async function retryDiscussions() { await refresh() }
</script>

<template>
  <header class="surface surface-pad">
    <span class="eyebrow">{{ search ? 'Search results' : 'Community feed' }}</span>
    <h1 class="serif-title" style="font-size:2rem;margin-top:8px">{{ search ? `Results for “${search}”` : 'Discussions' }}</h1>
    <p class="muted">Questions, ideas, and experiences shared by the community.</p>
  </header>
  <nav class="surface feed-tabs" aria-label="Discussion sort">
    <NuxtLink :to="{ path: '/discussions', query: { q: search } }" :aria-current="sort === 'latest' ? 'page' : undefined">Latest<span v-if="sort === 'latest'" class="feed-tab__accent" aria-hidden="true" /></NuxtLink>
    <NuxtLink :to="{ path: '/discussions', query: { q: search, sort: 'popular' } }" :aria-current="sort === 'popular' ? 'page' : undefined">Popular<span v-if="sort === 'popular'" class="feed-tab__accent" aria-hidden="true" /></NuxtLink>
  </nav>
  <div v-if="pending" class="surface surface-pad" role="status">Loading discussions…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">
    <p>Discussions are unavailable right now.</p>
    <button class="btn btn--outline btn--small" type="button" @click="retryDiscussions">Try again</button>
  </div>
  <EmptyState v-else-if="!result?.items.length" title="No discussions found" description="Try another search, or start a thoughtful conversation of your own." action-label="Create an account" action-to="/register" />
  <DiscussionCard v-for="item in result?.items || []" :key="item.id" :discussion="item" />
</template>
