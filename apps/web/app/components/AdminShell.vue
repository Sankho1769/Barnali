<script setup lang="ts">
const auth = useAuth()
async function signOut() {
  try { await auth.logout() } finally { await navigateTo('/admin/login') }
}

const nav = [
  { to: '/admin', label: 'Overview', icon: 'home', permission: 'OVERVIEW_READ' },
  { to: '/admin/rooms', label: 'Rooms', icon: 'messages', permission: 'ROOM_READ' },
  { to: '/admin/users', label: 'Users', icon: 'users', permission: 'USER_READ' },
  { to: '/admin/moderation', label: 'Reports', icon: 'flag', permission: 'REPORT_READ' },
  { to: '/admin/team', label: 'Admin team', icon: 'shield-check', permission: 'ADMIN_TEAM_READ' },
  { to: '/admin/audit', label: 'Audit log', icon: 'messages', permission: 'AUDIT_READ' }
]

function can(permission: string) {
  const permissions = auth.user.value?.permissions || []
  return permissions.includes('ALL') || permissions.includes(permission)
}
</script>

<template>
  <div class="admin-page">
    <header class="admin-topbar">
      <BarnaliBrand compact />
      <div class="topbar__actions">
        <span class="muted">{{ auth.user.value?.displayName }}</span>
        <button class="btn btn--outline btn--small" type="button" @click="signOut">Sign out</button>
      </div>
    </header>
    <div class="admin-shell">
      <nav class="admin-nav" aria-label="Administration">
        <NuxtLink v-for="item in nav.filter(item => can(item.permission))" :key="item.to" :to="item.to">
          <IconGlyph :name="item.icon" />{{ item.label }}
        </NuxtLink>
        <NuxtLink to="/" aria-label="Return to community"><IconGlyph name="compass" />View community</NuxtLink>
      </nav>
      <main class="admin-main"><slot /></main>
    </div>
  </div>
</template>
