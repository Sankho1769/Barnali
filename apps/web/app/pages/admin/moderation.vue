<script setup lang="ts">
import {getRequestError} from '~/utils/errors'
import type {PageResult,ReportRow} from '~/types/api'
definePageMeta({layout:'admin',middleware:'admin'})
useSeoMeta({title:'Reports and moderation · BARNALI',robots:'noindex,nofollow'})
const api=useApi();const status=ref('PENDING');const page=ref(0);const filter=computed(()=>`status=${status.value}&page=${page.value}&size=25`)
const {data,pending,error,refresh}=await useAsyncData('admin-reports',()=>api.request<PageResult<ReportRow>>(`/admin/reports?${filter.value}`),{watch:[filter]})
const message=ref('');const actionError=ref('');const busy=ref('')
async function resolve(report:ReportRow,action:'DISMISS'|'HIDE_TARGET'){
 const reason=window.prompt(`${action==='DISMISS'?'Dismiss':'Hide the reported content'} for ${report.targetType.toLowerCase()} ${report.targetId}. Enter an audit reason:`)
 if(!reason?.trim())return
 busy.value=report.id;message.value='';actionError.value=''
 try{await api.request(`/admin/reports/${report.id}/resolve`,{method:'POST',body:{action,reason}});message.value='Report updated.';await refresh()}
 catch(cause:unknown){actionError.value=getRequestError(cause,'The report could not be resolved.')}
 finally{busy.value=''}
}
</script>
<template>
 <div class="admin-heading"><div><span class="eyebrow">Safety</span><h1>Reports & moderation</h1><p>Review submitted reports and record the resolution reason in the audit trail.</p></div><label class="sr-only" for="report-status">Report status</label><select id="report-status" v-model="status" class="select" @change="page=0"><option value="PENDING">Pending</option><option value="RESOLVED">Resolved</option><option value="DISMISSED">Dismissed</option><option value="ALL">All reports</option></select></div>
 <p v-if="message" class="notice notice--success" role="status">{{message}}</p><p v-if="actionError" class="notice notice--error" role="alert">{{actionError}}</p>
 <div v-if="pending&&!data" class="surface surface-pad" role="status">Loading reports…</div><div v-else-if="error" class="notice notice--error" role="alert">{{getRequestError(error,'Reports are unavailable.')}} <button class="btn btn--outline btn--small" @click="refresh()">Retry</button></div><EmptyState v-else-if="!data?.items.length" title="No reports in this view" description="New community reports will appear here for authorized moderators." />
 <div v-else class="stack"><article v-for="report in data.items" :key="report.id" class="surface report-card"><div class="report-card__head"><span class="status-pill" :class="{'status-pill--muted':report.status!=='PENDING'}">{{report.status}}</span><strong>{{report.reason.replaceAll('_',' ')}}</strong><span class="muted">{{new Date(report.createdAt).toLocaleString()}}</span></div><h2>{{report.targetSummary||'Reported item unavailable'}}</h2><p class="muted">{{report.targetType}} · {{report.targetId}} · Reported by {{report.reporterName}}</p><p v-if="report.details">{{report.details}}</p><div v-if="report.status==='PENDING'" class="admin-controls"><button class="btn btn--danger btn--small" :disabled="busy===report.id||report.targetType==='USER'" :title="report.targetType==='USER'?'User suspension is managed from user details':''" @click="resolve(report,'HIDE_TARGET')">Hide content</button><button class="btn btn--outline btn--small" :disabled="busy===report.id" @click="resolve(report,'DISMISS')">Dismiss report</button></div></article><div class="pagination surface"><span>{{data.totalItems}} reports · Page {{page+1}} of {{Math.max(1,data.totalPages)}}</span><div class="admin-controls"><button class="btn btn--outline btn--small" :disabled="page===0" @click="page--">Previous</button><button class="btn btn--outline btn--small" :disabled="page+1>=data.totalPages" @click="page++">Next</button></div></div></div>
</template>
<style scoped>.report-card{padding:18px}.report-card h2{margin:12px 0 4px;font:700 1.25rem var(--b-font-display)}.report-card p{overflow-wrap:anywhere}.report-card__head{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.report-card__head .muted{margin-left:auto}</style>
