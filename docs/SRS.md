# Software requirements specification

## System boundary

The Nuxt web client renders public routes and sends requests to the Spring Boot API. Spring owns sessions, authorization, validation, PostgreSQL access, moderation decisions, analytics aggregation, and audit writes. Supabase is used only as a hosted PostgreSQL option; the browser does not call Supabase Data API or receive a service-role key.

## Functional requirements and status

| ID | Requirement | Status |
| --- | --- | --- |
| PUB-01 | Browse public discussions, rooms, and public discussion detail without an account. | IMPLEMENTED in source; live DB UNVERIFIED |
| PUB-02 | Search/sort public discussions and discover popular discussions/rooms. | IMPLEMENTED in source; search is discussions-focused |
| AUTH-01 | Register member accounts, sign in, sign out, and view own account summary. | IMPLEMENTED in source; auth integration UNVERIFIED |
| AUTH-02 | Change password, issue generic recovery response, and consume a one-time recovery token. | IMPLEMENTED in source; email delivery unavailable until SMTP is set |
| AUTH-03 | Verify email ownership and support MFA for privileged users. | PLANNED |
| COM-01 | Join a public room, create a discussion in a joined room, reply, vote, and report. | IMPLEMENTED in source; persistence UNVERIFIED |
| COM-02 | Bookmark, follow members/rooms, block members, show personalized feed, and deliver notifications/messages/realtime events. | PLANNED |
| ADM-01 | Enforce API authorization for each admin function and protect owner role. | IMPLEMENTED in source; API integration tests UNVERIFIED |
| ADM-02 | View operational metrics, analytics series, users, reports, rooms, admins, and audit records. | IMPLEMENTED in source; database results UNVERIFIED |
| ADM-03 | Invite/revoke delegated admins and change role/access. | IMPLEMENTED in source; invitation link delivery is manual without SMTP |
| ADM-04 | Custom permission bundles, export, system events/health, and settings. | PLANNED |
| SEO-01 | SSR public content with descriptive metadata and Open Graph descriptions. | PARTIAL; canonical URLs and sitemap are not configured |
| OPS-01 | Apply PostgreSQL schema migrations at API startup. | IMPLEMENTED in source; migration execution UNVERIFIED |

## Non-functional requirements

- **Security:** server-side sessions; CSRF tokens on mutating endpoints; BCrypt; request validation; exact configured credentialed CORS; bounded pages and analytics ranges; parameterized SQL; no-store headers for private API responses.
- **Performance:** JDBC statement timeout 5 seconds; Hikari pool sizing is configurable; analytics dates are bounded to 365 days; large user/report/audit lists paginate.
- **Availability:** UNVERIFIED. No deployment, monitoring, restore drill, or service-level objective is present.
- **Privacy:** activity events contain user ID, event type, timestamp only. No private-message table or device fingerprint collection exists.
- **Accessibility:** semantic forms, labels, visible focus styling and empty/error states exist in source. Formal WCAG or screen-reader verification is NOT PERFORMED.
- **Compatibility:** supported Node range is declared in `apps/web/package.json`; backend targets Java 21. Browser coverage is limited to the responsive smoke matrix documented in `TESTING.md`.

## Constraints and assumptions

PostgreSQL 15+ is required. `DATABASE_URL` and the HMAC secret must be configured. Supabase RLS policies were not inspected because no project was connected; the design uses server JDBC and does not expose browser database access. Email, MFA, realtime, backups, and monitoring require additional implementation or operator services.
