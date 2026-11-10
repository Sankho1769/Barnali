# Security guide

## Security boundary

The server is authoritative for credentials, session identity, roles, permissions, sensitive account data, admin writes, and database access. The browser receives no PostgreSQL password or Supabase service key. Route middleware only controls navigation; Spring Security and method-level authorization protect the APIs.

## Implemented controls

- BCrypt password hashes (strength 12); shared password policy enforces at least 12 characters, at most 72 UTF-8 bytes, and rejects common `password`, `welcome`, and `admin123` prefixes for member signup, owner bootstrap, and delegated-admin invitations.
- Owner bootstrap is excluded from ordinary API startup. Its isolated non-web command has an interactive approval gate; source now uses the separate owner-provisioner datasource, read-only readiness routine, BCrypt hashing, and one atomic one-time provisioning routine. V3 consumes a persistent guard so deleting the owner does not reopen bootstrap. This is source-only and not PostgreSQL-verified.
- HTTP-only, SameSite=Lax server session; 8-hour configured timeout; Secure in production; session-ID rotation at login; logout invalidation; auth-version checks for revoked sessions.
- CSRF token cookie/header for mutating requests and exact-origin credentialed CORS.
- HMAC-SHA256 rate-limit identifiers; seven failures in 15 minutes block for 15 minutes. Login, registration, and recovery endpoints share this service.
- Safe, generic recovery response; 30-minute one-time recovery tokens stored as SHA-256 hashes; no reset email unless SMTP is configured.
- Admin invitation tokens are random, single-use, hashed in storage, and expire after seven days. Only the owner can invite/change roles/revoke access. Owner-only API checks also require an active account and a completed bootstrap password change.
- Parameterized JDBC, validated request inputs, bounded page sizes/date ranges, 5-second PostgreSQL statement timeout, no-store headers on private API families.
- Audit trigger rejects updates/deletes; admin UI pages set noindex metadata.
- Production validator requires `APP_ENV=prod`, Secure cookie, and HTTPS-only application/API origins. `application-prod.yml` additionally sets production environment and Secure cookie when the Spring `prod` profile is active.

## Database roles and RLS

Use a dedicated migration principal that owns the barnali schema and has DDL authority only there, plus a separate runtime principal with no database/schema DDL, no schema ownership, and only the reviewed table/sequence/function privileges required by the API. Neither role may have BYPASSRLS. The current migration preflight refuses a shared principal and checks effective grants; its diagnostic output is not a grant script.

The existing public.rls_auto_enable() SECURITY DEFINER event trigger enables RLS on new tables. V1 and V2 remain unchanged; the additive V3 source migration defines scoped policies for all 14 tables, purpose-limited SECURITY DEFINER operations for authentication/recovery/invitations/reporting/analytics/bootstrap, five restricted views, and explicit ACLs. V3 has not been applied. The runtime principal must not have BYPASSRLS, and no production policy behavior has been verified.

The API source now installs a custom Spring JDBC transaction manager. After Spring binds the transaction connection, it sets `barnali.user_id` with parameterized `set_config(..., true)` on that same connection. Hikari initializes each physical connection with an empty identity baseline; PostgreSQL's transaction-local setting restores that baseline after commit or rollback. Only an authenticated `AppPrincipal` from `SecurityContextHolder` supplies the UUID; no request field, header, or role supplies database identity. Transactions without an application principal use an empty identity. Public registration, login lookup, recovery/reset, and invitation acceptance explicitly suppress any pre-existing session identity for the duration of those calls. If setup fails, the manager rolls back and unbinds the connection. Authenticated current-user, membership, admin-permission, admin dashboard, and team reads now have transaction boundaries. Admin permissions still come from the database's active `admin_accounts` row and the server permission matrix.

This is an application-to-database identity bridge, not protection from a compromised runtime credential or arbitrary SQL executed as the runtime role. PostgreSQL custom settings are not authenticated claims. Keep the credential server-only, continue using parameterized SQL, and never expose a SQL console through the application. V3 policies read the transaction-local setting, check active accounts for member operations, and derive admin permissions from admin_accounts. A compromised API process using the runtime credential could still set a custom GUC and impersonate an application identity; PostgreSQL integration tests and a separate threat review remain required.

**Unresolved identity trust boundary:** `barnali.user_id` is transaction-local and is sourced by the API from the server-side Spring principal, but PostgreSQL itself cannot authenticate the value in that custom setting. Any SQL injection, compromised API process, or leaked runtime credential that permits arbitrary SQL under `barnali_runtime` could set another UUID and exercise that user's RLS view. Transaction-local cleanup prevents connection reuse from leaking identity; it does not make the GUC unforgeable. Do not describe RLS as an independent defense against a fully compromised application runtime. Continue to parameterize every query, keep database credentials private, and prohibit arbitrary SQL interfaces.

The runtime does not query password hashes or token tables directly. `AppUserDetailsService` uses `auth_principal_by_email` for trusted Spring authentication; registration, reset, login limits, invitation acceptance, reports, and aggregates use narrow database routines. However, `auth_principal_by_email` returns the selected user's BCrypt password hash to the application runtime so Spring can verify the submitted password. This is a deliberate authentication boundary, not public API output, but compromise of the backend or runtime credential exposes hashes for offline guessing. Keep the function executable only by the runtime principal, never serialize or log its result, retain BCrypt's work factor, and consider moving credential verification behind a separately isolated authentication service if that threat becomes unacceptable. PostgreSQL tests should assert the runtime cannot select `users.password_hash` directly and that the function returns only the requested account's authentication fields.

Normal member and admin CRUD continues to use parameterized SQL under RLS. The provisioner receives no BARNALI table or sequence grants and only the two bootstrap function grants. The optional Testcontainers suite is designed to apply actual V1–V3 in a disposable PostgreSQL instance and verify the local ACL/RLS/function/view contracts. Until that suite has completed against PostgreSQL, function semantics, RLS evaluation, role ACLs, invitation/reset concurrency, pooled-connection cleanup, and owner provisioning remain unverified. Even a passing isolated suite would not verify Supabase role setup or its event trigger.

Keep the private barnali schema out of Supabase Data API exposed schemas.

Catalog inspection previously identified the event-trigger function owner as postgres and the event trigger as enabled for CREATE TABLE across schemas with a non-null command schema. Its privileged dynamic ALTER TABLE behavior makes it part of the database trust boundary. Its provenance is not established. Do not execute or change it as part of application setup. A DBA should separately review its owner, fixed search_path, identifier quoting, schema scope, and event-trigger attachment before production.

## Required operational practices

Use a deployment secret manager, exact allowed origins, HTTPS, trusted proxy configuration, backups and restore drills, monitoring, rate-limit review, and controlled owner recovery. Configure separate migration and runtime principals as above. See the deployment guide and threat model.

## Known gaps

MFA/TOTP, recovery codes, email verification, CSP/HSTS review, SMTP, private-message access controls, export permissions, and configurable audit/activity retention are not implemented or verified. Rate limiting uses `getRemoteAddr()`; behind a reverse proxy, do not trust arbitrary forwarding headers. Database-owner access can bypass audit triggers. The transaction behavior and V3 source contracts have offline test coverage only. No PostgreSQL integration test ran in this pass; actual policy evaluation, transaction-local cleanup on commit/rollback, Hikari reuse, function/view ACLs, and bootstrap remain unverified. **Not production-ready.**
