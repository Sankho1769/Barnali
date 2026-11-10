# Testing and quality checks

## Backend results refreshed 2026-10-24; frontend/browser results remain from 2026-10-22

These are source-level checks only; no production user data was used.

| Check | Command | Result |
| --- | --- | --- |
| Backend unit tests | `mvn -o -q test` from `apps/api` | 105 passed, 0 failures, 0 errors, 0 skipped (2026-10-24). Includes offline V3 source-contract checks and a static contract for the optional migration fixture; no PostgreSQL was used. Maven returned exit code 0. Its console also emitted `Access is denied.` and a JVM class-sharing warning; Surefire reports show all 105 tests passed. |
| PostgreSQL integration profile | `mvn -o -Ppostgres-integration -q test` from `apps/api` | Blocked before test compilation or execution: Testcontainers 2.0.5 JUnit and PostgreSQL artifacts are not cached, and offline Maven cannot reach Central. This workspace also has no `docker`, `podman`, `pg_ctl`, or `psql` command and no PostgreSQL/Docker TCP listener. No PostgreSQL test passed. |
| Frontend unit tests | Bundled Node 24.19.0 running vitest from apps/web | Previous result 2026-10-22: 2 passed, 0 failed; not rerun for this backend-only task. |
| Nuxt typecheck | Bundled Node 24.19.0 running Nuxt typecheck from apps/web | Previous result 2026-10-22: passed; not rerun for this backend-only task. |
| Nuxt production build | Bundled Node 24.19.0 running Nuxt build from apps/web | Previous result 2026-10-22: passed; not rerun for this backend-only task. |
| PowerShell scripts and selective environment import | AST parser over Import-BarnaliEnvironment.ps1, start-api.ps1, migrate-api.ps1, diagnose-migrations.ps1, and bootstrap-owner.ps1; separate temporary-file probe using synthetic values | Script syntax passed; the importer preserved special characters in a selected value and did not import an unselected synthetic secret. Parse/probe only; scripts were not executed against .env or a database. |
| In-app browser route check | Existing local Nuxt page at `http://localhost:3000` | Home and Login rendered. Rooms rendered its honest unavailable state. At 1440×900, the sidebar showed Trending News before Popular Rooms and both showed API-unavailable states. No sign-in was submitted. |
| In-app responsive state check | Browser viewport override on home | 36 requested viewport settings: widths 320, 360, 375, 390, 430, 768, 1024, 1280, 1440, 1920, 2560, 3440; heights 600, 900, 1200. The accessibility tree matched the mobile navigation breakpoint through 760 px, desktop navigation above it, and right rail above 1180 px at all 36 settings. This did not measure document overflow or check other routes at every size. |

The `node` command on `PATH` is v22.16.0, below the package requirement `^22.22.3 || ^24.15.0 || >=26.0.0`. A compatible bundled Node v24.19.0 was used for the frontend checks; package versions and the engine declaration were not changed.

The responsive script remains configured for `/`, `/explore`, `/login`, `/rooms`, `/admin/login` at the width/height matrix above. The in-app browser check covered only the home route's navigation and rail visibility; its page-overflow assertion remains unverified.

## Not tested / blocked

- No PostgreSQL integration test actually ran. The opt-in Testcontainers suite now prepares a disposable PostgreSQL 17 database, provisions synthetic migration/runtime/provisioner roles and a private schema, then applies the real V1–V3 migrations. Its checks cover migration history, role/table/sequence/function/view privileges, RLS visibility and cross-user isolation, authentication and recovery routines, report/moderator/admin boundaries, restricted view shape/ownership, transaction-local identity across commit/rollback and pooled connection reuse, concurrent recovery completion, and concurrent one-time owner provisioning including deletion afterward. It is prepared but uncompiled/unexecuted here because the profile artifacts are missing offline and no compatible runtime is installed.
- V1/V2 remain unchanged. The additive V3 source migration defines RLS policies and restricted auth/admin functions, but no PostgreSQL database has executed it. The 105 offline tests include static source-contract checks; they do not validate V3 SQL syntax, grants, RLS execution, or function behavior on PostgreSQL. The existing identity-only container fixture still uses its synthetic table/policy; the new separate V3 fixture is the migration-backed test.
- Flyway migrations were inspected but not applied to PostgreSQL/Supabase.
- The prepared migration fixture exercises database function and policy boundaries, but it is not an HTTP/Spring Security end-to-end test. Login, logout, CSRF, recovery email delivery, browser sessions, and every API route still lack complete end-to-end coverage.
- Analytics date-boundary and known-value assertions, migration upgrade from an already populated schema, and Supabase event-trigger behavior still require real PostgreSQL/Supabase-specific verification.
- No authenticated admin dashboard browser test; no successful authentication was simulated.
- No automated accessibility/axe, screen-reader, keyboard-only, contrast, load, or security scanner run.
- The responsive state check does not measure horizontal overflow and does not include detail routes or admin pages behind authentication.
- This task did not read .env or connect to Supabase. The last user-reported read-only preflight said PostgreSQL authentication and SELECT 1 succeeded with SSL enforced, schema barnali and Flyway history were absent, V1/V2 were pending, and runtime/migration credentials still resolved to a shared BYPASSRLS principal with database CREATE. Those live facts were not refreshed here.
- No Spring API startup, migration, schema/role change, owner bootstrap, or database write was attempted in this task. Current live connectivity and privileges remain unverified by these offline tests.
- V1/V2 migration files remain unchanged. They were not applied. No live post-migration RLS or table-privilege behavior is verified.

## Repeat checks

Use the commands in [Development](DEVELOPMENT.md). The responsive script needs the local server and Chrome. Record versions, dates, routes, dimensions, and failure counts when results change. Run frontend checks on a Node version allowed by `apps/web/package.json`. Do not claim a test passed if its command did not complete successfully.
