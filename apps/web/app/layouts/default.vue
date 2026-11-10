<script setup lang="ts">
const auth = useAuth()
if (!auth.checked.value) await auth.refresh()
</script>

<template>
  <div class="page-shell">
    <AppHeader />
    <div class="community-grid">
      <CommunityNavigation />
      <main class="feed-column"><slot /></main>
      <aside class="right-rail"><CommunityRightRail /></aside>
    </div>
  </div>
</template>
