<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
import type { AdminUserRow, PageResult } from '~/types/api'
definePageMeta({layout:'admin',middleware:'admin'})
useSeoMeta({title:'User management · BARNALI',robots:'noindex,nofollow'})
const api=useApi();const search=ref('');const status=ref('');const sort=ref('created');const page=ref(0);const pageSize=25
const params=computed(()=>`q=${encodeURIComponent(search.value)}&status=${encodeURIComponent(status.value)}&sort=${sort.value}&page=${page.value}&size=${pageSize}`)
const {data,pending,error,refresh}=await useAsyncData('admin-user-directory',()=>api.request<PageResult<AdminUserRow>>(`/admin/users?${params.value}`),{watch:[params]})
const message=ref('');const actionError=ref('');const busy=ref('')
async function setStatus(user:AdminUserRow){
  const next=user.status==='ACTIVE'?'SUSPENDED':'ACTIVE'
  const reason=window.prompt(`${next==='SUSPENDED'?'Suspend':'Reinstate'} ${user.displayName}. Enter a reason for the audit log:`)
  if(!reason?.trim())return
  busy.value=user.id;actionError.value='';message.value=''
  try{await api.request(`/admin/users/${user.id}/status`,{method:'PATCH',body:{status:next,reason}});message.value=`Account ${next.toLowerCase()}.`;await refresh()}
  catch(cause:unknown){actionError.value=getRequestError(cause,'The account status could not be changed.')}
  finally{busy.value=''}
}
</script>

<template>
  <div class="admin-heading"><div><span class="eyebrow">Directory</span><h1>User management</h1><p>Search accounts and review activity totals. Credentials and private messages are never shown.</p></div></div>
  <p v-if="message" class="notice notice--success" role="status">{{message}}</p><p v-if="actionError" class="notice notice--error" role="alert">{{actionError}}</p>
  <div class="table-toolbar"><label class="sr-only" for="user-search">Search name, username, or email</label><input id="user-search" v-model="search" class="search-input" type="search" placeholder="Search name, username, or email…" @input="page=0">
    <div class="admin-controls"><label class="sr-only" for="user-status">Account status</label><select id="user-status" v-model="status" class="select" @change="page=0"><option value="">All statuses</option><option value="ACTIVE">Active</option><option value="SUSPENDED">Suspended</option></select><label class="sr-only" for="user-sort">Sort users</label><select id="user-sort" v-model="sort" class="select"><option value="created">Newest</option><option value="activity">Recent activity</option><option value="name">Name</option></select></div></div>
  <div v-if="pending&&!data" class="surface surface-pad" role="status">Loading user directory…</div>
  <div v-else-if="error" class="notice notice--error" role="alert">{{getRequestError(error,'The user directory is unavailable.')}} <button class="btn btn--outline btn--small" @click="refresh()">Retry</button></div>
  <EmptyState v-else-if="!data?.items.length" title="No matching users" description="Try changing your filters, or wait for accounts to register." />
  <div v-else class="surface table-wrap"><table class="data-table"><thead><tr><th>User</th><th>Email</th><th>Status</th><th>Created</th><th>Last activity</th><th>Rooms</th><th>Posts / replies</th><th>Action</th></tr></thead><tbody>
    <tr v-for="user in data.items" :key="user.id"><td><NuxtLink :to="`/admin/users/${user.id}`"><strong>{{user.displayName}}</strong></NuxtLink><br><span class="muted">@{{user.username}} · {{user.role}}</span></td><td>{{user.email}}<br><span class="muted">{{user.emailVerified?'Verified':'Unverified'}}</span></td><td><span class="status-pill" :class="{'status-pill--danger':user.status==='SUSPENDED'}">{{user.status}}</span></td><td class="nowrap">{{new Date(user.createdAt).toLocaleDateString()}}</td><td>{{user.lastActivityAt?new Date(user.lastActivityAt).toLocaleString():'No events'}}</td><td>{{user.roomCount}}</td><td>{{user.discussionCount}} / {{user.replyCount}}</td><td><button v-if="user.role==='MEMBER'" class="btn btn--outline btn--small" type="button" :disabled="busy===user.id" @click="setStatus(user)">{{busy===user.id?'Saving…':user.status==='ACTIVE'?'Suspend':'Reinstate'}}</button><span v-else class="muted">Team managed</span></td></tr>
  </tbody></table><div class="pagination"><span>{{data.totalItems}} users · Page {{data.page+1}} of {{Math.max(1,data.totalPages)}}</span><div class="admin-controls"><button class="btn btn--outline btn--small" :disabled="page===0" @click="page--">Previous</button><button class="btn btn--outline btn--small" :disabled="page+1>=data.totalPages" @click="page++">Next</button></div></div></div>
</template>
