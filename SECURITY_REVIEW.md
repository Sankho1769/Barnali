# BARNALI security review

**Date:** 2026-10-22  
**Scope:** Source-level review of the Nuxt client, Spring Security configuration, API authorization, authentication services, migrations, and environment setup. No live database or deployment was available.

## Summary

**Status: NOT READY FOR PRODUCTION.** The application uses server-side sessions, CSRF protection, BCrypt, database-backed role checks, single-use invitation/recovery tokens, and append-only application audit records. These controls are implemented in source and partly unit-tested. MFA, live database verification, email verification, and operational validation remain incomplete.

## Findings

| Severity | Status | Finding and action |
| --- | --- | --- |
| High | FAIL | MFA/TOTP and recovery codes are not implemented. Add and test MFA for the owner and privileged administrators before production use. |
| High | BLOCKED | No Supabase/PostgreSQL instance or credentials were supplied. Migration execution, cookie/session behavior against the deployed origin, concurrency, database grants, and API authorization were not integration-tested. |
| Medium | FAIL | Email verification is not implemented; an account created through public registration can sign in before address ownership is verified. Add verified-email gating and abuse controls before opening registration broadly. |
| Medium | BLOCKED | SMTP is unconfigured. Recovery responses remain generic but no reset mail is sent; invitation URLs must be handed to recipients through a trusted channel. Configure and test a mail provider before advertising these flows. |
| Medium | FAIL | CSP and a formal deployment security-header review are pending. Current source sets frame denial, content-type protection, and a strict-origin referrer policy. |
| Medium | BLOCKED | Rate limiting uses `HttpServletRequest.getRemoteAddr()`. Production reverse-proxy forwarding must be configured and trusted narrowly, or clients behind the proxy may share one address bucket. Do not trust arbitrary forwarded headers. |
| Low | FIXED | Admin invitation acceptance for an existing suspended account previously risked a database lookup error. It now returns a deliberate unavailable-account rejection. Verification: backend tests and compilation pass; no DB integration test is available. |
| Low | FIXED | Owner bootstrap previously could update the password of an existing user with the configured owner email if no owner row existed. It now refuses an email already in use; operators must use the controlled recovery path. Verification: backend tests and compilation pass. |

## Controls present in source

- BCrypt strength 12; password policy requires at least 12 characters and caps UTF-8 input at 72 bytes.
- Initial owner password is environment-only, not included in source, migrations, or `.env.example`; first login requires password change. Bootstrap does not reset an existing account, and the owner cannot be demoted or revoked through team endpoints.
- Registration cannot choose an admin role. API checks server-resolved session identity and an explicit role-permission matrix; UI guards are navigation only.
- Opaque server-side session, HttpOnly cookie, SameSite=Lax, 8-hour timeout, session ID rotation on login, logout invalidation, auth-version revocation after sensitive access/password changes, and CSRF tokens on mutating requests.
- Credentialed CORS is restricted to the configured application origin. Production startup requires `APP_ENV=prod`, a Secure session cookie, and HTTPS-only `APP_ORIGIN` and `API_ORIGIN` values.
- Login, registration, and recovery use HMAC-keyed identity/address rate-limit buckets. Seven failures in a 15-minute window block the bucket for 15 minutes; raw email/IP identifiers are not stored in the rate-limit table. Blocked-login audit writes are throttled.
- Password recovery uses a generic response, a random single-use 30-minute token stored as a hash, and session revocation on reset. Without SMTP, delivery is intentionally unavailable.
- Admin invitations use random single-use tokens, store token hashes, expire after seven days, and can be revoked. The invitation URL is returned only once at creation; keep it private.
- SQL data access is parameterized; page sizes and analytics ranges are bounded; sort keys are selected from an allowlist; JDBC sessions use a 5-second statement timeout.
- Flyway can use separate `MIGRATION_DATABASE_*` credentials; if unset, it falls back to the API connection. Configure a distinct DDL-capable migration account in production so the runtime role can remain least-privileged.
- `admin_audit_events` has a database trigger rejecting update/delete and application code only appends. A database owner can still alter the trigger/storage; this is not tamper-proof against database administrators.
- Private auth/admin/self API responses receive `Cache-Control: private, no-store`. Admin web pages use `noindex,nofollow`; these are defense-in-depth and do not replace access control.
- The API accesses PostgreSQL server-side; the browser receives no database password or Supabase service key. Supabase Data API/RLS is not used by this design. If browser/Data API access is later added, review and enable RLS before exposing tables.

## Role boundaries

The role matrix is fixed in `apps/api/src/main/java/com/barnali/api/admin/PermissionMatrix.java`; only the owner can issue invitations, change delegated roles, or revoke/restore administrator access. Delegated roles cannot assign `OWNER`. The full permissions are listed in `ADMIN_PANEL_IMPLEMENTATION_REPORT.md`.

## Secrets and environment

`.env.example` contains the owner email specified in the supplied requirements, but no password, database password, HMAC key, SMTP credential, or token. Keep real values in an untracked `.env` for local development and a deployment secret manager in production. The service refuses to start without a random HMAC key of at least 32 bytes and a reachable configured database. Use `APP_ENV=dev` locally; the server default is production mode and validates Secure cookies and HTTPS origins.

## Required before production

1. Implement and test MFA for owner and privileged roles.
2. Provision staging PostgreSQL/Supabase, apply migrations, and test bootstrap, CSRF/session cookies, login/logout, password change/recovery, role checks, invitations, audit triggers, and analytics queries end to end.
3. Add verified email, configure SMTP, and test deliverability, abuse limits, bounce handling, and reset/invitation expiry.
4. Validate proxy trust, HTTPS, CORS, CSP, cookie settings, database least privilege, connection limits, backup/restore, migration operations, alerting, and incident recovery.
5. Add database integration tests and focused API authorization tests for every role, including owner protection and privileged mutation denial.
