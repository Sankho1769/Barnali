# Feature dependencies and delivery status

| Feature | Status | Dependency or next work |
| --- | --- | --- |
| Public discussion/room pages | Source implemented; DB UNVERIFIED | PostgreSQL/Supabase, staged migration and SSR checks. |
| Search/discovery | Source implemented for discussions and popular rooms | Room search and ranking can follow after product definition. |
| Trending News | UI, typed API contract, and unavailable response implemented; no news data source configured | Add an approved server-side provider with attribution, freshness, caching, and HTTPS URLs. No live headlines are shown in this workspace. |
| Secure session auth | Source implemented; live auth UNVERIFIED | DB, origin/cookie/CSRF integration tests, MFA and verified-email flow. |
| Member participation | Source includes join, create, reply, vote, report | DB integration tests; abuse controls and moderation capacity. |
| Bookmarks | Not implemented | Bookmark table, member-scoped API, UI state, revoke/uniqueness tests. |
| Following | Not implemented | Follow graph, privacy rules, follow APIs, personalized feed contract. |
| Personalized feed | Not implemented | Requires a product-defined ranking and follows/activity signals; current home feed is general latest. |
| Notifications | Not implemented | Notification persistence, read state, delivery policy, user preferences; no provider configured. |
| Private messages | Not implemented | Threat/privacy model, conversation membership checks, retention/block/report rules, encrypted transport/storage policy, API and audit decisions. |
| Blocking | Not implemented | Block relation plus visibility and contact rules across feeds, rooms, replies, and messages. |
| Real time | Not implemented | Verified hosting constraints, authenticated WebSocket/SSE/Supabase Realtime design, reconnect/event contract. No simulated live behavior exists. |
| SEO canonical/sitemap | Partial | Canonical URL policy, host configuration, sitemap generation, HTTP status/metadata integration checks. |
| Admin analytics | SQL source implemented; real values UNVERIFIED | Provision PostgreSQL, populate privacy-conscious events, validate aggregates on known fixtures. |
| MFA/email verification | Not implemented | Design/enrollment/recovery policy, implementation, mail provider, privileged-role enforcement and tests. |
| Admin custom permissions | Fixed five-role matrix | Product approval and DB/API model for user-defined permission sets, if required. |
| Export/system health/events/settings | Not implemented | Authorization, retention, format/audit, monitoring/provider definitions and API implementation. |
| Accessibility verification | Not performed | Automated audit plus keyboard, contrast, zoom, reduced-motion, and screen-reader checks. |
| Production operations | Not configured | Secrets manager, staging migration, TLS/proxy, least privilege, backups, restore drills, alerts, rollback. |
