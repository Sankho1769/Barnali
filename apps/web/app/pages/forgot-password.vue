<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
definePageMeta({layout:false})
useSeoMeta({title:'Password recovery · BARNALI',robots:'noindex,nofollow'})
const api=useApi();const email=ref('');const busy=ref(false);const error=ref('');const message=ref('')
async function submit(){busy.value=true;error.value='';message.value='';try{const result=await api.request<{message:string}>('/auth/password/recovery',{method:'POST',body:{email:email.value.trim()}});message.value=result.message}catch(e:unknown){error.value=getRequestError(e,'Recovery is temporarily unavailable.')}finally{busy.value=false}}
</script>
<template><main class="auth-panel-wrap"><section class="auth-panel surface surface-pad"><BarnaliBrand compact/><span class="eyebrow">Account recovery</span><h1 class="serif-title">Forgot your password?</h1><p class="muted">Enter the email connected to your account. If recovery delivery is enabled, instructions will be sent.</p><p v-if="message" class="notice notice--success" role="status">{{message}}</p><p v-if="error" class="notice notice--error" role="alert">{{error}}</p><form class="auth-form" @submit.prevent="submit"><div class="form-field"><label for="recovery-email">Email address</label><input id="recovery-email" v-model="email" class="input" type="email" autocomplete="email" maxlength="254" required></div><button class="btn" :disabled="busy">{{busy?'Submitting…':'Send recovery request'}}</button></form><NuxtLink to="/login">Back to sign in</NuxtLink></section></main></template>
