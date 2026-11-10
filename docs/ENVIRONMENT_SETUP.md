# Environment and deployment setup

## Required values

Create an untracked `.env` from `.env.example`.

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | JDBC URL for Supabase Postgres or local PostgreSQL. For Supabase shared Session Pooler, copy the exact host from Dashboard → Connect, use port `5432`, and include `sslmode=require`. |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Dedicated server-side application database credentials. |
| `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` | Non-secret project reference copied independently from Supabase Dashboard project URL/settings; must match both Session Pooler username suffixes. |
| `APP_SECURITY_HMAC_KEY` | Random secret of at least 32 bytes for privacy-preserving rate-limit keys. |
| `APP_ORIGIN` | Exact trusted browser origin for credentialed CORS. |
| `SESSION_COOKIE_SECURE` | `true` in HTTPS production. |
| `OWNER_EMAIL` | Initial owner identity; this is not a password. |
| `OWNER_BOOTSTRAP_PASSWORD` | One-time secret supplied through the deployment environment. Do not commit it or pass it on a command line. |

Migration variables MIGRATION_DATABASE_URL, MIGRATION_DATABASE_USERNAME, and MIGRATION_DATABASE_PASSWORD are mandatory for the isolated Flyway diagnostic and apply operation. There is no runtime-credential fallback. Configure a distinct migration principal for the same approved target, and set `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` from an independent, non-secret Dashboard source. The diagnostic validates both username suffixes against the expected value; do not infer project identity from matching usernames alone. NUXT_PUBLIC_API_BASE sets the web API base URL. API_PORT, DATABASE_POOL_SIZE, and SMTP settings also exist in application.yml; inspect that file before adding deployment-specific overrides.

Supabase documents custom PostgreSQL role management and a Dashboard role manager at **Database → Roles**. Create barnali_runtime and barnali_migrator as separate login roles with separate passwords. Use the UI only if it exposes the restricted attributes and password controls needed. Otherwise create the roles without a password literal and set each password with the interactive psql password prompt; PostgreSQL documents this prompt as keeping cleartext out of command history and server logs. Leave SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS, and memberships that grant privileges to either app role disabled. Supabase may have a platform-managed incoming membership from postgres with SET and INHERIT disabled; check effective state rather than trying to remove Supabase-owned membership. See [DATABASE.md](DATABASE.md#private-schema-plan) for the separately approved schema-owner path. The role manager's ability to assign schema ownership is not documented; the SQL Editor path is documented there.

`SMTP_*` settings are optional. Without them, password reset mail is not available; the response stays generic. Owner-created admin invitations can be copied once and delivered through a trusted channel. Never send a permanent password.

For the Supabase shared Session Pooler, use `barnali_runtime.<PROJECT-REF>` for the runtime username and `barnali_migrator.<PROJECT-REF>` for the migration username. Copy the pooler host rather than composing it from a region. Confirm the project reference independently from the Dashboard URL/settings, then set `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` to that non-secret reference. Use port `5432`, database `postgres`, and `sslmode=require` in both JDBC URLs. Do not use transaction-pool mode for the persistent API/Flyway connections. Supabase documents the connection formats and SSL option in its [PostgreSQL connection guide](https://supabase.com/docs/guides/database/connecting-to-postgres).

## Owner setup

Owner bootstrap remains blocked until RLS policies are designed for a runtime role without `BYPASSRLS`. The existing event trigger enables RLS on V1/V2 tables, and V1/V2 add no policies; table grants alone do not allow the runtime role to access those tables. Once reviewed policies and runtime access are tested, supply OWNER_BOOTSTRAP_PASSWORD through a deployment secret manager or process-scoped secret injection; the isolated bootstrap script never imports this password from .env. Do not put the password in source, command arguments, or shell history. The isolated non-web operation checks target, SSL, migration history, effective role privileges, RLS flags, and owner-state consistency using read-only queries before the transactional owner write. It BCrypt-hashes the password, creates only when there is no OWNER, and requires password change at first sign-in. It does not replace an active owner. If the configured email already belongs to a member, bootstrap fails closed rather than changing that member or granting administrator access.

For recovery after loss of the owner, use the documented database backup and controlled operator procedure. Do not create an owner by editing a request, browser storage, or user profile.

## Database and migrations

Ordinary API startup has Flyway disabled and does not invoke owner bootstrap. Run .\scripts\diagnose-migrations.ps1 for read-only preflight after separate roles and environment settings exist. Apply readiness requires no inherited memberships for either connected app role, SSL, correct project identity, and the migrator owning barnali without database-wide CREATE. Use the temporary administrative SET ROLE path in [DATABASE.md](DATABASE.md#private-schema-plan) for schema ownership; it does not grant database-wide CREATE to the migrator. The migration command requires explicit MIGRATION_DATABASE_* values and never falls back to runtime credentials. It runs a non-web isolated entry point, keeps Flyway clean, baseline, and schema creation disabled, and performs read-only post-migration checks. Do not start the API or owner bootstrap until the RLS identity bridge, policies, and tests described in [SECURITY.md](SECURITY.md#database-roles-and-rls) are implemented and runtime readiness passes. Back up before approved deployment changes. Keep service-role and database keys server-side. Do not use transaction-pool mode for Flyway migration sessions.

## Local startup (PowerShell)

1. Use Node.js 22.22.3+ or 24.15+, Java 21+, and Maven 3.9+.
2. Configure .env with reachable runtime and migration PostgreSQL settings, the HMAC key, and APP_ENV=dev. Do not put OWNER_BOOTSTRAP_PASSWORD in .env; the owner operation requires it to be injected from a secret manager or process-scoped protected source.
3. After reviewing the read-only preflight, request explicit approval before schema creation and, separately, before migration apply. Once migrations and runtime grants are verified, bootstrap the owner with the isolated command described above. Then start the API with .\scripts\start-api.ps1; it does not run migrations or owner bootstrap.
4. Install web dependencies with `npm install --prefix apps/web`.
5. Start Nuxt with `npm run dev --prefix apps/web`.
6. Open `http://localhost:3000`.

Migrations apply in order (`V1` schema, then `V2` login audit throttling and duplicate-report guard). The bootstrap secret is only required if the database has no owner record. After initial owner sign-in, change the password at `/account/password`; the session is invalidated and the owner must sign in again.

## Production

- Run behind HTTPS; set `APP_ENV=prod`, `SESSION_COOKIE_SECURE=true`, and exact HTTPS `APP_ORIGIN`/`API_ORIGIN` values. Set `SPRING_PROFILES_ACTIVE=prod` to load `application-prod.yml`; `APP_ENV` and the Spring profile are separate settings.
- Put all secrets in the host's secret manager. Fail deployment if the HMAC key or database credentials are missing.
- Set separate least-privilege runtime and DDL migration roles, pool size, query timeout, backups, alerting, and restore drills before serving real users.
- Configure SMTP before advertising account recovery or automated invitation delivery.
- Add MFA and verified-email flows before real member/admin operations.
- Test the migration and rollback procedure against a restored staging copy. Flyway migrations are forward-only; rollback is a reviewed compensating migration or database restore.
- No production database, mail provider, MFA provider, or backup service is attached to this workspace, so production activation is not verified.
