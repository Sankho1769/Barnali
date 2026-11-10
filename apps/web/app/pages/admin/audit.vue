<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
import type {AuditRow,PageResult} from '~/types/api'
definePageMeta({layout:'admin',middleware:'admin'})
useSeoMeta({title:'Administrative audit log · BARNALI',robots:'noindex,nofollow'})
const api=useApi();const action=ref('');const page=ref(0);const params=computed(()=>`page=${page.value}&size=50${action.value?`&action=${encodeURIComponent(action.value)}`:''}`)
const {data,pending,error,refresh}=await useAsyncData('admin-audit',()=>api.request<PageResult<AuditRow>>(`/admin/audit?${params.value}`),{watch:[params]})
</script>
<template>
 <div class="admin-heading"><div><span class="eyebrow">Security</span><h1>Audit log</h1><p>Append-only record of sensitive administrator activity. Entries cannot be edited or deleted through the application.</p></div></div>
 <div class="table-toolbar"><label class="sr-only" for="audit-action">Filter by action</label><input id="audit-action" v-model="action" class="search-input" type="search" placeholder="Filter exact action, e.g. USER_SUSPENDED" @input="page=0"></div>
 <div v-if="pending&&!data" class="surface surface-pad" role="status">Loading audit events…</div><div v-else-if="error" class="notice notice--error" role="alert">{{getRequestError(error,'Audit log unavailable.')}} <button class="btn btn--outline btn--small" @click="refresh()">Retry</button></div><EmptyState v-else-if="!data?.items.length" title="No audit events yet" description="Security-sensitive actions will appear when the system records them." />
 <div v-else class="surface table-wrap"><table class="data-table"><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Outcome</th><th>Reason</th></tr></thead><tbody><tr v-for="row in data.items" :key="row.id"><td class="nowrap">{{new Date(row.createdAt).toLocaleString()}}</td><td>{{row.actorName||'System / unauthenticated'}}</td><td>{{row.action}}</td><td>{{row.targetType}}<br><span class="muted">{{row.targetId||'—'}}</span></td><td><span class="status-pill" :class="{'status-pill--danger':row.outcome==='FAILURE'||row.outcome==='DENIED'}">{{row.outcome}}</span></td><td>{{row.reason||'—'}}</td></tr></tbody></table><div class="pagination"><span>{{data.totalItems}} events · Page {{page+1}} of {{Math.max(1,data.totalPages)}}</span><div class="admin-controls"><button class="btn btn--outline btn--small" :disabled="page===0" @click="page--">Previous</button><button class="btn btn--outline btn--small" :disabled="page+1>=data.totalPages" @click="page++">Next</button></div></div></div>
</template>
