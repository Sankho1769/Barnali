<script setup lang="ts">
import type { TrendingNewsItem } from '~/types/api'

defineProps<{
  state: 'loading' | 'error' | 'available' | 'unavailable'
  items: TrendingNewsItem[]
}>()

defineEmits<{ retry: [] }>()

function safeHttpsUrl(value: string | null) {
  if (!value) return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && url.hostname && !url.username && !url.password ? url.href : null
  } catch {
    return null
  }
}

function relativePublicationTime(value: string) {
  const publishedAt = Date.parse(value)
  if (!Number.isFinite(publishedAt)) return 'Publication time unavailable'

  const deltaSeconds = Math.round((publishedAt - Date.now()) / 1000)
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 31_536_000], ['month', 2_592_000], ['day', 86_400], ['hour', 3_600], ['minute', 60]
  ]
  const [unit, seconds] = units.find(([, length]) => Math.abs(deltaSeconds) >= length) || ['second', 1]
  return new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' }).format(Math.round(deltaSeconds / seconds), unit)
}
</script>

<template>
  <section class="surface rail-card news-panel" aria-labelledby="trending-news-heading">
    <h2 id="trending-news-heading">Trending news</h2>
    <div v-if="state === 'loading'" class="news-state muted" role="status">Loading trusted news…</div>
    <div v-else-if="state === 'error'" class="news-state" role="alert">
      <p class="muted">Trending news is temporarily unavailable.</p>
      <button class="btn btn--outline btn--small" type="button" @click="$emit('retry')">Try again</button>
    </div>
    <p v-else-if="state === 'unavailable'" class="news-state muted" role="status">
      No trusted news source is configured yet.
    </p>
    <p v-else-if="!items.length" class="news-state muted" role="status">There are no current stories to show.</p>
    <ol v-else class="news-list">
      <li v-for="(item, index) in items" :key="`${item.publisher}-${item.articleUrl}`" class="news-item">
        <span class="news-item__rank" aria-hidden="true">{{ index + 1 }}</span>
        <a
          v-if="safeHttpsUrl(item.articleUrl)"
          class="news-item__link"
          :href="safeHttpsUrl(item.articleUrl) || undefined"
          target="_blank"
          rel="noopener noreferrer"
          :aria-label="`${item.headline}, from ${item.publisher}, opens in a new tab`"
        >
          <img v-if="safeHttpsUrl(item.thumbnailUrl)" class="news-item__image" :src="safeHttpsUrl(item.thumbnailUrl) || undefined" alt="" loading="lazy">
          <span v-else class="news-item__image news-item__image--empty" aria-hidden="true"><IconGlyph name="messages" :size="19" /></span>
          <span class="news-item__copy">
            <strong>{{ item.headline }}</strong>
            <span class="news-item__meta">{{ item.publisher }} <span aria-hidden="true">·</span> <time :datetime="item.publishedAt">{{ relativePublicationTime(item.publishedAt) }}</time></span>
          </span>
          <IconGlyph name="external-link" :size="16" />
        </a>
        <span v-else class="news-item__link news-item__link--unavailable">
          <span class="news-item__image news-item__image--empty" aria-hidden="true"><IconGlyph name="messages" :size="19" /></span>
          <span class="news-item__copy"><strong>{{ item.headline }}</strong><span class="news-item__meta">{{ item.publisher }}</span></span>
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.news-panel { padding: 16px; }
.news-panel h2 { margin-bottom: 12px; }
.news-state { display: grid; justify-items: start; gap: 10px; min-height: 64px; align-content: center; font-size: .84rem; line-height: 1.45; }
.news-state p { margin: 0; }
.news-list { display: grid; gap: 12px; margin: 0; padding: 0; list-style: none; }
.news-item { display: grid; grid-template-columns: 18px minmax(0,1fr); gap: 8px; align-items: start; }
.news-item__rank { padding-top: 5px; color: var(--b-vermilion); font-family: var(--b-font-display); font-size: 1.08rem; font-weight: 700; text-align: center; }
.news-item__link { display: grid; grid-template-columns: 56px minmax(0,1fr) 16px; gap: 9px; align-items: center; min-width: 0; color: var(--b-ink); text-decoration: none; }
.news-item__link:hover strong { color: var(--b-teal-dark); text-decoration: underline; text-underline-offset: 2px; }
.news-item__image { width: 56px; height: 52px; border-radius: 8px; object-fit: cover; background: var(--b-sage); }
.news-item__image--empty { display: grid; place-items: center; color: var(--b-forest); }
.news-item__copy { display: grid; gap: 4px; min-width: 0; }
.news-item__copy strong { display: -webkit-box; overflow: hidden; color: #16231d; font-size: .82rem; font-weight: 700; line-height: 1.28; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.news-item__meta { display: flex; flex-wrap: wrap; gap: 4px; color: var(--b-muted); font-size: .7rem; line-height: 1.25; }
.news-item__link > :last-child { color: var(--b-teal-dark); }
.news-item__link--unavailable { grid-template-columns: 56px minmax(0,1fr); }
@media(max-width: 1180px) and (min-width: 761px) { .news-list { grid-template-columns: repeat(2,minmax(0,1fr)); } }
</style>
