<script setup lang="ts">
const auth = useAuth()
const links = [
  { to: '/', label: 'Home', icon: 'home' },
  { to: '/explore', label: 'Explore', icon: 'compass' },
  { to: '/rooms', label: 'Rooms', icon: 'messages' },
  { to: '/discussions', label: 'Discussions', icon: 'message-circle' }
]
</script>

<template>
    <nav class="side-nav" aria-label="Community">
      <NuxtLink v-for="item in links" :key="item.to" class="side-nav__link" :to="item.to">
        <IconGlyph :name="item.icon" />{{ item.label }}
      </NuxtLink>
      <NuxtLink v-if="auth.user.value && auth.user.value.role !== 'MEMBER'" class="side-nav__link" to="/admin"><IconGlyph name="shield-check" />Admin panel</NuxtLink>
      <div class="side-nav__divider" />
      <NuxtLink v-if="auth.user.value" class="side-nav__link" to="/profile"><IconGlyph name="users" />Profile</NuxtLink>
      <NuxtLink v-else class="side-nav__link" to="/register"><IconGlyph name="plus" />Create account</NuxtLink>
      <div class="side-brand-card">
        <img src="/assets/brand/logos/barnali-mark.svg" alt="">
        <p>A community for curious minds to learn, share and grow together.</p>
      </div>
    </nav>
    <nav class="mobile-nav" aria-label="Mobile navigation">
      <NuxtLink v-for="item in links.slice(0, 3)" :key="item.to" :to="item.to">
        <IconGlyph :name="item.icon" :size="21" /><span>{{ item.label }}</span>
      </NuxtLink>
      <NuxtLink :to="auth.user.value ? '/profile' : '/login'">
        <IconGlyph name="users" :size="21" /><span>{{ auth.user.value ? 'Profile' : 'Sign in' }}</span>
      </NuxtLink>
    </nav>
</template>
