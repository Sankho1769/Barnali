# BARNALI admin panel implementation report

**Date:** 2026-10-22  
**Status:** Implemented as a database-backed source slice; live service behavior is not verified. **Production verdict: NOT READY.**

## Implemented

- Nuxt 4 / Vue 3 / TypeScript app using preserved BARNALI logos, icons, UI assets, design styles, and brand component. Responsive public site includes the community feed, popular discovery, public SEO discussion pages, rooms, about/guidelines, login, registration, profile, and password flows.
- Spring Boot 4 API with Spring Security 7 session authentication, PostgreSQL JDBC, Flyway migrations, request validation, consistent safe errors, server-side authorization, and bounded SQL analytics queries.
- Public read endpoints for published discussions and public rooms; signed-in APIs for room membership, creating discussions/replies, voting, and reporting.
- Admin UI for overview/charts, user directory and detail, report moderation, room management, owner-controlled team/invitations, audit log, admin sign-in, access denied, and invitation acceptance. Admin routes are guarded in the UI and every protected API is independently authorized.
- Empty, loading, denied, and unavailable UI states are used instead of fake accounts, metrics, sample posts, or simulated successful operations.

## API routes

Public reads: `GET /api/v1/public/discussions`, `GET /api/v1/public/discussions/{slug}`, `GET /api/v1/public/rooms`, `GET /api/v1/public/rooms/{slug}`.

Auth: `GET /api/v1/auth/csrf`, `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, `POST /api/v1/auth/logout`, `POST /api/v1/auth/password/change`, `POST /api/v1/auth/password/recovery`, `POST /api/v1/auth/password/reset`, `POST /api/v1/auth/admin-invitations/accept`.

Community mutations: `GET /api/v1/community/me/rooms`, `POST /api/v1/discussions`, `POST /api/v1/rooms/{slug}/join`, `POST /api/v1/discussions/{slug}/replies`, `POST /api/v1/discussions/{slug}/vote`, `POST /api/v1/reports`.

Admin: `GET /api/v1/admin/overview`, `GET /api/v1/admin/analytics`, `GET /api/v1/admin/users`, `GET /api/v1/admin/users/{id}`, `PATCH /api/v1/admin/users/{id}/status`, `GET /api/v1/admin/reports`, `POST /api/v1/admin/reports/{id}/resolve`, `GET /api/v1/admin/audit`, `GET /api/v1/admin/rooms`, `POST /api/v1/admin/rooms`, `PATCH /api/v1/admin/rooms/{id}/active`, `GET /api/v1/admin/team`, `POST /api/v1/admin/team/invitations`, `POST /api/v1/admin/team/invitations/{id}/revoke`, `PATCH /api/v1/admin/team/{id}/role`, `PATCH /api/v1/admin/team/{id}/active`.

## Roles and permissions

| Role | Implemented permissions |
| --- | --- |
| Owner | Overview, analytics, user read/suspend, room read/manage, report read/resolve, admin team read/manage, audit read. Owner identity is protected from ordinary demotion, suspension, or removal. |
| Super Admin | Same operational permissions except owner-only team changes. |
| Moderator | Overview, user read/suspend, room read, report read/resolve, audit read. |
| Analytics Viewer | Overview, analytics, room read. |
| Support Admin | Overview, user read, audit read. |

Delegated admin invitations are owner-only, single-use, token-hash-at-rest, and expire after seven days. Role assignment is from a server-side fixed enum; custom roles and arbitrary permission combinations are not implemented. Admin access is suspended/restored instead of deleting history.

## Metrics

All reporting uses UTC boundaries. The requested range is limited to 365 days and defaults to the latest 30 days.

| Metric/chart | Definition |
| --- | --- |
| Registered users | Current count of all user records. |
| New registrations | Users whose `created_at` falls within the selected inclusive UTC date range. |
| Active users (selected period) | Distinct users with a row in `user_activity_events` in that period. |
| DAU / WAU / MAU | Distinct users with recorded events in today / last 7 UTC days / last 30 UTC days, independent of selected range. |
| Rooms | All rooms, active and inactive. |
| Published discussions/replies | Current rows with `PUBLISHED` status. |
| Pending reports | Current `PENDING` report count. |
| Suspended accounts | Current users with `SUSPENDED` status. |
| Registration chart | New user rows per UTC day. |
| Daily active chart | Distinct event users per UTC day. |
| Community activity chart | Count per UTC day of tracked `LOGIN`, `ROOM_JOIN`, `DISCUSSION_CREATE`, `REPLY_CREATE`, and `VOTE` events. |

The activity event model starts when populated; historical activity is not inferred from account creation. Retention, comparison periods, returning-user cohorts, device statistics, message volume, and notification delivery metrics are not presented because the required data is not available or collected.

## Database changes

- `V1__barnali_community_and_admin.sql`: users, rooms/memberships, discussions, replies, votes, reports, activity events, admin accounts/invitations, login limits, reset tokens, audit events, constraints/indexes, and append-only audit trigger.
- `V2__rate_limit_audit_and_report_guard.sql`: throttled blocked-address audit timestamp and unique guard against duplicate pending reports by reporter/target.
- Database and migrations are present but were not executed against a real PostgreSQL or Supabase database in this workspace.

## Important source areas

- `apps/web/app/pages` and `apps/web/app/components`: public, auth, and admin pages/components.
- `apps/web/app/composables/useApi.ts`, `useAuth.ts`: API/session client and auth state.
- `apps/api/src/main/java/com/barnali/api/auth`: session, registration, login limits, bootstrap, password recovery.
- `apps/api/src/main/java/com/barnali/api/admin`: analytics, team/invitations, roles, permissions, moderation, audit.
- `apps/api/src/main/java/com/barnali/api/config`: security, CORS, production validation, error handling, no-store headers.
- `apps/api/src/main/resources/db/migration`: schema migrations.
- Root `README.md`, `docs/ARCHITECTURE.md`, `docs/ENVIRONMENT_SETUP.md`, and `SECURITY_REVIEW.md`: setup and risk documentation.

## Verification results

- Backend: `mvn test` in `apps/api` — 6 passed, 0 failed. This includes role matrix, community mutation, and production configuration unit tests. No DB integration tests ran.
- Frontend: `node node_modules/vitest/vitest.mjs run` in `apps/web` — 2 tests passed.
- Frontend types: `node node_modules/nuxt/bin/nuxt.mjs typecheck` — passed.
- Frontend build: `node node_modules/nuxt/bin/nuxt.mjs build` — passed using bundled Node 24 runtime.
- Browser: responsive smoke test checked 180 combinations on `/`, `/explore`, `/login`, `/rooms`, and `/admin/login`; widths 320, 360, 375, 390, 430, 768, 1024, 1280, 1440, 1920, 2560, 3440 and heights 600, 900, 1200 px; zero failures. The protected dashboard itself was not browser-checked because no live admin session/database was available; no fake authenticated state or analytics was used.
- Automated authentication, DB migration, endpoint/RBAC integration, analytics fixture, accessibility, load, and deployment tests were not available or not implemented. Do not infer these passed from compilation or page rendering.

## Pending or blocked

- MFA/TOTP, email verification, custom permissions, automated email delivery, a standalone posts/replies review queue, private messages, notifications/system events, data export, system health/settings, retention/cohort analytics, account-level report history, and date filtering for user lists are not implemented.
- No production Supabase/PostgreSQL, SMTP, MFA service, backup provider, or monitoring endpoint is configured. Live database results and successful authentication have not been verified.
- Before production, follow `docs/ENVIRONMENT_SETUP.md` and complete the high-priority items in `SECURITY_REVIEW.md`.
