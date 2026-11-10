<script setup lang="ts">
const auth = useAuth()
const searchText = ref('')

async function submitSearch() {
  const query = searchText.value.trim()
  if (query) await navigateTo({ path: '/discussions', query: { q: query } })
}

onMounted(() => {
  if (!auth.checked.value) void auth.refresh()
})
</script>

<template>
  <header class="topbar">
    <div class="topbar__brand"><BarnaliBrand compact /></div>
    <nav class="topbar__links" aria-label="Main navigation">
      <NuxtLink to="/">Home</NuxtLink>
      <NuxtLink to="/rooms">Rooms</NuxtLink>
      <NuxtLink to="/discussions">Discussions</NuxtLink>
      <NuxtLink to="/explore">Discover</NuxtLink>
      <NuxtLink to="/about">About</NuxtLink>
    </nav>
    <form class="topbar__search-wrap" role="search" @submit.prevent="submitSearch">
      <label class="sr-only" for="global-search">Search discussions and rooms</label>
      <input id="global-search" v-model="searchText" class="topbar__search" type="search" placeholder="Search discussions or rooms…">
    </form>
    <div class="topbar__actions">
      <NuxtLink v-if="auth.user.value" class="topbar__profile" to="/profile">
        <span class="topbar__avatar" aria-hidden="true">{{ auth.user.value.displayName.slice(0, 1).toUpperCase() }}</span>
        <span>{{ auth.user.value.displayName }}</span>
      </NuxtLink>
      <NuxtLink v-else class="topbar__login" to="/login">Sign in</NuxtLink>
    </div>
  </header>
</template>

<style scoped>
.topbar__search-wrap { margin-inline-start: auto; width: min(31vw, 410px); }
@media (max-width: 760px) { .topbar__search-wrap { width: auto; flex: 1; } }
</style>
