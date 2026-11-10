<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
import type {AdminUserRow,TimePoint} from '~/types/api'
definePageMeta({layout:'admin',middleware:'admin'})
useSeoMeta({title:'User details · BARNALI',robots:'noindex,nofollow'})
interface Detail{user:AdminUserRow;activity:TimePoint[]}
const api=useApi();const route=useRoute();const id=computed(()=>String(route.params.id))
const {data,pending,error,refresh}=await useAsyncData(()=>`admin-user-${id.value}`,()=>api.request<Detail>(`/admin/users/${encodeURIComponent(id.value)}`),{watch:[id]})
</script>
<template>
 <div class="admin-heading"><div><span class="eyebrow">User record</span><h1>{{data?.user.displayName||'User details'}}</h1><p>Private account information visible to authorized administrators.</p></div><NuxtLink class="btn btn--outline" to="/admin/users">Back to users</NuxtLink></div>
 <div v-if="pending&&!data" class="surface surface-pad" role="status">Loading user record…</div><div v-else-if="error" class="notice notice--error" role="alert">{{getRequestError(error,'User record unavailable.')}} <button class="btn btn--outline btn--small" @click="refresh()">Retry</button></div>
 <template v-else-if="data"><section class="surface surface-pad user-detail"><dl><div><dt>Username</dt><dd>@{{data.user.username}}</dd></div><div><dt>Email</dt><dd>{{data.user.email}} · {{data.user.emailVerified?'Verified':'Unverified'}}</dd></div><div><dt>Status</dt><dd>{{data.user.status}}</dd></div><div><dt>Role</dt><dd>{{data.user.role}}</dd></div><div><dt>Created</dt><dd>{{new Date(data.user.createdAt).toLocaleString()}}</dd></div><div><dt>Last recorded activity</dt><dd>{{data.user.lastActivityAt?new Date(data.user.lastActivityAt).toLocaleString():'No activity events recorded'}}</dd></div><div><dt>Rooms</dt><dd>{{data.user.roomCount}}</dd></div><div><dt>Discussions / replies</dt><dd>{{data.user.discussionCount}} / {{data.user.replyCount}}</dd></div></dl></section><TrendChart title="Recorded activity" description="Event count per UTC day for the last 30 days." :points="data.activity"/><p class="notice">Activity is based on recorded login, room join, discussion, reply, and vote events. It does not include private messages or historical events that were not captured.</p></template>
</template>
<style scoped>.user-detail{margin-bottom:16px}.user-detail dl{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:0}.user-detail dl div{min-width:0}.user-detail dt{font-size:.78rem;color:var(--b-muted);text-transform:uppercase;letter-spacing:.05em}.user-detail dd{margin:4px 0 0;font-weight:650;overflow-wrap:anywhere}@media(max-width:600px){.user-detail dl{grid-template-columns:1fr}}</style>
