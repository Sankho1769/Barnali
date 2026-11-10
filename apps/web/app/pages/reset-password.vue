<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
definePageMeta({layout:false})
useSeoMeta({title:'Set a new password · BARNALI',robots:'noindex,nofollow'})
const route=useRoute();const api=useApi();const password=ref('');const busy=ref(false);const error=ref('');const message=ref('')
async function submit(){if(typeof route.query.token!=='string'){error.value='This recovery link is missing its token.';return}busy.value=true;error.value='';try{const result=await api.request<{message:string}>('/auth/password/reset',{method:'POST',body:{token:route.query.token,password:password.value}});message.value=result.message}catch(e:unknown){error.value=getRequestError(e,'This recovery link is invalid or expired.')}finally{busy.value=false}}
</script>
<template><main class="auth-panel-wrap"><section class="auth-panel surface surface-pad"><BarnaliBrand compact/><span class="eyebrow">Account recovery</span><h1 class="serif-title">Choose a new password</h1><p v-if="message" class="notice notice--success" role="status">{{message}} <NuxtLink to="/login">Sign in</NuxtLink></p><p v-if="error" class="notice notice--error" role="alert">{{error}}</p><form class="auth-form" @submit.prevent="submit"><div class="form-field"><label for="new-password">New password</label><input id="new-password" v-model="password" class="input" type="password" autocomplete="new-password" minlength="12" maxlength="72" required></div><button class="btn" :disabled="busy||!!message">{{busy?'Updating…':'Set new password'}}</button></form></section></main></template>
