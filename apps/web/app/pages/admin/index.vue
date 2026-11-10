<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
import type { AdminAnalytics, AdminOverview } from '~/types/api'
definePageMeta({ layout: 'admin', middleware: 'admin' })
useSeoMeta({ title: 'Admin overview · BARNALI', robots: 'noindex, nofollow' })
const api=useApi()
const auth=useAuth()
const days=ref('30')
const to=new Date().toISOString().slice(0,10)
const from=computed(()=>{const date=new Date(`${to}T00:00:00Z`);date.setUTCDate(date.getUTCDate()-Number(days.value)+1);return date.toISOString().slice(0,10)})
const canSeeAnalytics=computed(()=>auth.user.value?.permissions.includes('ANALYTICS_READ')||false)
type DashboardData={overview:AdminOverview;registrations?:AdminAnalytics['registrations'];activeUsers?:AdminAnalytics['activeUsers'];engagement?:AdminAnalytics['engagement']}
const {data, pending, error, refresh}=await useAsyncData<DashboardData>('admin-dashboard',async()=>{
  if(canSeeAnalytics.value)return api.request<AdminAnalytics>(`/admin/analytics?from=${from.value}&to=${to}`)
  return {overview:await api.request<AdminOverview>(`/admin/overview?from=${from.value}&to=${to}`)}
},{watch:[from,canSeeAnalytics]})
const errorMessage=computed(()=>error.value?getRequestError(error.value,'Analytics could not be loaded. Check the API and database connection.'): '')
</script>

<template>
  <div class="admin-heading"><div><span class="eyebrow">Administration</span><h1>Overview</h1><p>Live aggregates from BARNALI's PostgreSQL database, reported in UTC.</p></div>
    <div class="admin-controls"><label class="sr-only" for="analytics-range">Reporting period</label><select id="analytics-range" v-model="days" class="select"><option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="90">Last 90 days</option><option value="365">Last 365 days</option></select></div></div>
  <div v-if="pending && !data" class="surface surface-pad" role="status">Loading analytics…</div>
  <div v-else-if="errorMessage" class="notice notice--error" role="alert">{{ errorMessage }} <button class="btn btn--outline btn--small" type="button" @click="refresh()">Retry</button></div>
  <template v-else-if="data">
    <p class="muted admin-refresh">Period {{ data.overview.from }} to {{ data.overview.to }} · Refreshed {{ new Date(data.overview.refreshedAt).toLocaleString() }}</p>
    <div class="kpi-grid">
      <KpiCard label="Registered users" :value="data.overview.totalUsers" hint="All accounts currently in the database" />
      <KpiCard label="New registrations" :value="data.overview.newUsers" hint="Created in the selected UTC period" />
      <KpiCard label="Active users" :value="data.overview.activeUsers" hint="Distinct accounts with a recorded event in period" />
      <KpiCard label="DAU" :value="data.overview.dau" hint="Distinct users with events today (UTC)" />
      <KpiCard label="WAU" :value="data.overview.wau" hint="Distinct users with events in the last 7 UTC days" />
      <KpiCard label="MAU" :value="data.overview.mau" hint="Distinct users with events in the last 30 UTC days" />
      <KpiCard label="Rooms" :value="data.overview.totalRooms" hint="All rooms, active or inactive" />
      <KpiCard label="Published discussions" :value="data.overview.totalDiscussions" hint="Currently published discussions" />
      <KpiCard label="Published replies" :value="data.overview.totalReplies" hint="Currently published replies" />
      <KpiCard label="Pending reports" :value="data.overview.pendingReports" hint="Awaiting moderator review" />
      <KpiCard label="Suspended accounts" :value="data.overview.suspendedUsers" hint="Current suspended user accounts" />
    </div>
    <div v-if="canSeeAnalytics" class="chart-grid">
      <TrendChart title="New registrations" description="Accounts created each day (UTC)." :points="data.registrations||[]" />
      <TrendChart title="Daily active users" description="Distinct users with a tracked event each day (UTC)." :points="data.activeUsers||[]" />
      <TrendChart title="Community activity" description="Login, room join, discussion, reply, and vote events recorded each day." :points="data.engagement||[]" />
    </div>
    <p v-else class="notice">Your role can view operational totals. Detailed analytics charts require the Analytics Viewer or a broader role.</p>
    <p class="notice admin-data-note">Activity metrics start when the event table is introduced and populated. Historical activity is not inferred from registration dates. No comparison or retention percentage is shown because a verified baseline is not available.</p>
  </template>
</template>

<style scoped>.admin-refresh{margin:-12px 0 14px;font-size:.82rem}.admin-data-note{margin-top:16px}</style>
