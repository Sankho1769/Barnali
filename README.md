# BARNALI

BARNALI is a topic-based community for curious minds. The workspace contains the Nuxt web app and Spring API source alongside the original preserved brand asset kit.

## Workspace

- `apps/web` — Nuxt 4, Vue 3, TypeScript, public pages, auth forms, and admin UI.
- `apps/api` — Spring Boot 4, Java 21 target, Spring Security, PostgreSQL JDBC, and Flyway.
- `BARNALI_Asset_Kit` — preserved original brand assets and integration documentation.
- `docs` — product, system, security, operations, and user documentation.
- Root audit reports: [website audit](WEBSITE_AUDIT_REPORT.md), [security review](SECURITY_REVIEW.md), and [admin implementation report](ADMIN_PANEL_IMPLEMENTATION_REPORT.md).

No Git metadata is present in this workspace, so repository history and a release changelog cannot be verified. `BGC.docx` and unrelated Infinity Blog prompts are not BARNALI specifications.

## Implemented scope

The current source includes public discussion and room reads, search/discovery, registration and session sign-in, own-profile view, room joins, discussion/reply creation, voting, reporting, and a protected admin area for overview metrics, user review/status, report moderation, room management, admin invitations/access, and audit history. Discussion cards expose the existing vote/report APIs and browser sharing. Trending News has an optional server-side provider contract but no configured provider, so the UI states that news is unavailable without showing invented stories. Admin and public operations call backend API paths. The last user-reported read-only diagnostic confirmed PostgreSQL authentication, SELECT 1, and SSL, while the barnali schema and Flyway history were absent and V1/V2 pending. This task did not reconnect, so the live state was not refreshed.

Bookmarks, following, private messaging, notifications, real-time delivery, blocking, email verification, MFA, custom permission sets, data export, system-event/health dashboards, and settings are not implemented. The home feed is a general latest-discussion feed and does not implement personalization. Canonical URLs and sitemap generation are not configured. See [feature dependencies](docs/FEATURE_DEPENDENCIES.md).

## Local development

Requirements: Node.js 22.22.3+ or 24.15+, Java 21+, Maven 3.9+, and PostgreSQL 15+ or Supabase PostgreSQL. Copy .env.example to an untracked .env, set APP_ENV=dev, runtime DB credentials and APP_SECURITY_HMAC_KEY, and provide all three MIGRATION_DATABASE_* values for the isolated preflight and migration operation. Migration credentials must identify the same project and remain distinct from runtime credentials. Do not store OWNER_BOOTSTRAP_PASSWORD in .env or source; use a process-scoped secret manager only for owner provisioning.

From PowerShell, run the read-only preflight with .\scripts\diagnose-migrations.ps1. After separate approval, create the private schema, rerun the preflight, then separately approve migrations with .\scripts\migrate-api.ps1 -Apply and type APPLY BARNALI PRIVATE SCHEMA MIGRATIONS. After reviewing runtime grants, securely inject OWNER_BOOTSTRAP_PASSWORD and run .\scripts\bootstrap-owner.ps1 -Apply with confirmation CREATE BARNALI OWNER. The ordinary API startup has Flyway and owner bootstrap disabled; start it only after setup with .\scripts\start-api.ps1. In a second terminal run npm install --prefix apps/web and npm run dev --prefix apps/web; open http://localhost:3000. See the database, security, development, environment, and deployment guides for role design, RLS limitations, and rollback steps.

## Verification

Use `npm run typecheck --prefix apps/web`, `npm test --prefix apps/web`, and `npm run build --prefix apps/web`. Run `mvn test` from `apps/api`. The responsive smoke script requires the web server at port 3000 and local Chrome. Actual last-run results and limits are recorded in [Testing](docs/TESTING.md). No production database or mail provider is attached; the project is **not ready for production**.

## Documentation

Start at [docs/README.md](docs/README.md). Architecture, database, API, design, development, testing, security, deployment, user, and admin guides are linked there. The separate documentation-only master prompt referenced by the build request was not found in the workspace or supplied text attachments; this documentation follows the explicit documentation list in the current BARNALI master prompt.
