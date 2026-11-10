# Product requirements document

**Product:** BARNALI community platform  
**Status:** Working source foundation; live service verification blocked by absent database and mail configuration.

## Product purpose

BARNALI helps people learn through respectful topic-based conversation. Public visitors should be able to discover public discussions and rooms. Participation requires an account. Moderation and administrator actions must be permissioned and auditable.

## Users

- **Visitor:** reads public discussion and room content; can discover, search, and register.
- **Member:** signs in, joins rooms, publishes discussions, replies, votes, and reports content.
- **Moderator:** reviews reports and may hide reported discussions/replies or restrict member accounts as permitted.
- **Analytics Viewer:** reads overview and analytics data.
- **Support Admin:** limited user review and audit access.
- **Super Admin:** broad operations access, without owner-only team control.
- **Owner:** initial privileged identity, controls administrator invitations and delegated roles.

## Implemented product slice

| Capability | Status | Evidence/limit |
| --- | --- | --- |
| Public feed, discovery, rooms, discussion detail | IMPLEMENTED in source | Reads public API endpoints; actual content requires PostgreSQL. |
| Search | IMPLEMENTED in source | Discussion query supports server-side search; room search is not present. |
| Registration, login, logout, password change/recovery | IMPLEMENTED in source | SMTP delivery and database integration are unverified; email verification is absent. |
| Room membership, discussion/reply creation, voting, reporting | IMPLEMENTED in source | Authenticated API operations; no live mutation test or database connection. |
| User profile | PARTIAL | Own account summary and password change; public profile pages/editing are not implemented. |
| Admin dashboard and SQL analytics | IMPLEMENTED in source | Counts and charts are backed by SQL; no live numbers are bundled or verified. |
| Admin team, roles, invitations, audit | IMPLEMENTED in source | Fixed role matrix; owner-only team mutations; email delivery not configured. |
| Bookmarks, following, personalized feed, notifications, private messaging, blocking, real time | PLANNED | No database model or API currently implements these capabilities. |
| MFA, email verification, custom roles, exports, system health/events, settings | PLANNED | Not implemented; see security and feature-dependency docs. |

## Product principles

1. Public content is readable without signing in; mutations require a server-authenticated member.
2. The server resolves identity and permissions. Client route guards are convenience only.
3. Empty, loading, unavailable, and error states are honest; the UI does not invent content or success.
4. Collect only the activity data needed for the documented analytics.
5. Use BARNALI's botanical brand and accessible component patterns.

## Success and release criteria

Local source readiness requires typecheck, unit tests, production build, and responsive smoke verification. Production release additionally requires a provisioned database, staged migrations and integration tests, MFA for privileged accounts, verified-email and SMTP decisions, backup/restore and monitoring, production security configuration, and review of the unresolved items in `SECURITY.md`.
