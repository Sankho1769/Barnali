# Next-phase source gap analysis

Reviewed 2026-10-22 against the current Nuxt, Spring, SQL, configuration, and test sources. No Git metadata is present, so Git status/diff could not be obtained. Existing workspace files and the original asset kit were preserved.

Database preflight was subsequently repeated read-only on 2026-10-23. PostgreSQL authentication, `SELECT 1`, and SSL succeeded; the application schema is now planned as private `barnali` because Flyway cannot initialize `public` while `public.rls_auto_enable()` exists without a history table. The schema and migrations remain unapplied.

## Implemented in source

- Nuxt 4/Vue 3 pages for public discussion/room browsing, member auth forms, and admin overview, users, moderation, rooms, team, and audit.
- Spring JDBC REST API, PostgreSQL Flyway migrations, server-side sessions, BCrypt, CSRF, login throttling, account status/auth-version checks, fixed admin roles, SQL-backed analytics, invitations, and append-only audit events.
- Owner bootstrap for `banerjeeshubham00@gmail.com` through `OWNER_EMAIL` and a one-time environment-supplied password. The source will not bootstrap until the DB is reachable and a strong bootstrap secret is supplied.

Implementation presence does not establish a successful database connection or real login. The owner identity has not been provisioned in this workspace.

## Partially implemented

- Authentication and protected admin operations are implemented in source, but PostgreSQL-backed flows and unauthorized/authorized HTTP requests were not exercised. This pass fixed owner-only authorization to enforce active-account and completed-password-change requirements, unified password policy across signup/owner bootstrap/admin invitations, and made invitation-acceptance audit events identify the accepting user as actor.
- Admin charts and directories use JDBC aggregates/queries, but no live database data or fixture-driven analytics results were verified.
- Supabase PostgreSQL connectivity is verified read-only through the Session Pooler, including PostgreSQL authentication, `SELECT 1`, and enforced SSL. The private `barnali` schema remains absent, and V1/V2 are pending. The browser calls Spring; it does not connect to the Supabase Data API.
- Password reset and admin invitation delivery require SMTP. Without it, reset delivery is unavailable and invitation links must be shared securely by the owner.
- Public pages render through Nuxt and set page titles/descriptions, but canonical URLs, sitemap generation, and route-specific social share images are incomplete.
- The home feed now labels its actual latest-discussion ordering correctly. Feed cards expose the existing vote/report APIs and browser sharing; no Follow, bookmark, or personalization controls are shown without backing features.
- Trending News now has a typed API contract and optional server-side provider seam. No provider is configured; the endpoint returns `UNAVAILABLE` and no live story is fabricated. The sidebar places this panel above DB-backed Popular Rooms.

## Missing

- The checked-in migrations contain no RLS enablement or policies, no database-role creation, and no grant statements. Do not expose these tables through Supabase Data API without designing and testing RLS. When separate migration and runtime roles are used, runtime grants need operator provisioning.
- MFA, verified email, bookmarks, follows, personalized feed, private messaging, blocking, notifications, real-time delivery, export, and system health/settings dashboards are not present.
- A trusted external news provider and a separate news listing destination (`See all`) are not configured/implemented. People-you-may-know recommendations remain omitted because no authorized recommendation source exists.
- Database integration tests, migration tests, authenticated admin browser tests, and accessibility/security scanner runs are not present.

## Unverified and blocked here

- The current read-only checks verified the configured Session Pooler target, PostgreSQL authentication, `SELECT 1`, and SSL. `barnali` is absent and V1/V2 are pending; no migration has run. The runtime and migration sessions use the same non-superuser role, which has `BYPASSRLS` and database `CREATE`. The public event trigger enables RLS on new tables in `barnali`; the role bypasses RLS, and no table grants/policies are present for a separate runtime principal.
- Real owner bootstrap, sign-in, protected API access, database-backed charts, and production deployment remain unverified. Owner state is not established by this status summary; inspect it through the read-only diagnostic before deciding whether bootstrap configuration is needed.
- Playwright could not connect to the local server (`ERR_NETWORK_ACCESS_DENIED`). The in-app browser did load home/login/rooms and completed the home navigation/rail visibility check at 36 viewport settings, but it could not verify data/API behavior or horizontal overflow. Maven tests, frontend unit tests, typecheck, and production build did run; current results are in [testing](TESTING.md).
- `node` on `PATH` is v22.16.0, below the package's declared range. Bundled Node v24.19.0 satisfies the existing range and completed frontend checks; no dependency or manifest upgrade was needed.

## Safe next steps

1. After separate explicit approval, create the private `barnali` schema using the SQL documented in [the database guide](DATABASE.md), then rerun the read-only diagnostic to confirm schema ownership, permissions, and barnali-first search path.
2. Keep V1/V2 unchanged and review the additive V3 source migration. Before any database writes, provision the three distinct roles and private schema, run read-only checks, then run the kept PostgreSQL integration suite with synthetic users and non-BYPASSRLS runtime role. Only after that review, seek separate explicit approval to apply V1/V2/V3.
3. Supply a strong, one-time `OWNER_BOOTSTRAP_PASSWORD` through a protected local environment for the configured owner email, then remove it after bootstrap.
4. Apply migrations only to a disposable/staging DB, test registration/login/session/CSRF and admin authorization, then run analytics with known fixtures.
5. Re-run browser checks in an environment where the browser process can reach the local server.
