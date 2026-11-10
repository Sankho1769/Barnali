<script setup lang="ts">
definePageMeta({middleware:'member'})
useSeoMeta({title:'My profile · BARNALI',robots:'noindex,nofollow'})
const auth=useAuth()
</script>
<template><section v-if="auth.user.value" class="surface surface-pad"><span class="eyebrow">Your account</span><h1 class="serif-title" style="font-size:2rem;margin-top:8px">{{auth.user.value.displayName}}</h1><p class="muted">@{{auth.user.value.username}}</p><p>{{auth.user.value.email}}</p><NuxtLink class="btn btn--outline" to="/account/password">Change password</NuxtLink><NuxtLink v-if="auth.user.value.role!=='MEMBER'" class="btn" to="/admin">Open admin panel</NuxtLink></section></template>
