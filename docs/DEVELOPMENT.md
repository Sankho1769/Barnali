# Development guide

## Prerequisites

- Node.js 22.22.3+ or 24.15+ (declared in `apps/web/package.json`).
- Java 21 or newer, Maven 3.9+.
- PostgreSQL 15+ or a Supabase PostgreSQL project for database-backed routes.
- Chrome installed if running the responsive smoke script.

## Configure and start

1. Copy .env.example to .env. Set APP_ENV=dev, runtime DATABASE_URL/DATABASE_USERNAME/DATABASE_PASSWORD, `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` from Dashboard project URL/settings (independent of the role suffix), and APP_SECURITY_HMAC_KEY (at least 32 bytes). Keep migration database credentials separate and configure all three MIGRATION_DATABASE_* values before migration diagnostics or apply. Do not put OWNER_BOOTSTRAP_PASSWORD in .env; inject it through a process-scoped secret manager only when explicitly provisioning the first owner. Do not commit .env.
2. Start the ordinary API only after the database schema/migrations and owner setup are ready: from PowerShell run .\scripts\start-api.ps1. This entry point has Flyway disabled and does not register owner bootstrap.
3. In another terminal, run `npm install --prefix apps/web` and `npm run dev --prefix apps/web`.
4. Browse `http://localhost:3000`; API default is `http://localhost:8080`.

For a read-only preflight, run .\scripts\diagnose-migrations.ps1. Review the independently verified project reference, target, SSL, schema ownership and role separation, role memberships/inherited privileges, effective grants across non-system schemas and through PUBLIC, migration history, and RLS metadata. Current apply readiness requires zero role memberships for both database principals; if any are reported, resolve them through the approved role-management interface and rerun diagnostics. If the schema is absent, an authorized operator must create it and assign ownership to barnali_migrator; do not give the migrator database-wide CREATE. Rerun diagnostics and require VERIFIED_MIGRATOR_OWNS_SCHEMA_WITHOUT_DATABASE_CREATE before separately approving migration. Then run .\scripts\migrate-api.ps1 -Apply; type APPLY BARNALI PRIVATE SCHEMA MIGRATIONS when prompted. The runner is isolated, non-web, uses explicit MIGRATION_DATABASE_* credentials, keeps clean/baseline disabled, and performs post-migration read-only RLS/grant checks. It does not start the normal API or owner bootstrap.

After V3, runtime grants, and bootstrap readiness are independently verified, inject OWNER_BOOTSTRAP_PASSWORD from a secret manager into the current PowerShell process; OWNER_EMAIL is read from .env. Run .\scripts\bootstrap-owner.ps1 -Apply and type CREATE BARNALI OWNER. This isolated non-web operation uses the execute-only provisioner principal and atomic persistent guard. It requires a password change at first sign-in. The local source design is not PostgreSQL-verified. Do not grant BYPASSRLS or DDL privileges to runtime.

If SMTP is absent, reset email is not delivered and admin invitation links must be shared securely by the owner.

## Useful commands

```powershell
npm run typecheck --prefix apps/web
npm test --prefix apps/web
npm run build --prefix apps/web
mvn -o -q test
```

Run Maven commands from `apps/api`. `npm run test:responsive --prefix apps/web` expects Nuxt running on port 3000 and Chrome at the default Windows path; set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` if needed.

### Isolated PostgreSQL migration and security tests

The PostgreSQL integration fixtures are opt-in and separate from the ordinary offline suite. They use the Maven profile `postgres-integration` and Testcontainers to start disposable PostgreSQL 17 databases with synthetic roles and data. The V3 fixture creates separate `barnali_migrator`, `barnali_runtime`, and `barnali_provisioner` logins, creates a private `barnali` schema owned by the migrator, and applies the checked-in V1, V2, and V3 migrations in order through Flyway. It checks the actual migration-created ACLs, RLS policies, auth/recovery/admin functions, views, transaction-local identity, and the owner guard. The fixture never reads `.env`, does not contact Supabase or another external database, does not start Spring Boot, and does not call the owner's provisioning runner. Its only data and database are created inside the disposable container.

Run the complete PostgreSQL profile from `apps/api` with:

```powershell
mvn -Ppostgres-integration -q test
```

A Docker-compatible container runtime must be installed and running, and Maven must have the Testcontainers artifacts available. If Docker is unavailable, the integration classes are skipped by Testcontainers; a skipped run is not PostgreSQL verification. The normal `mvn -o -q test` command excludes the optional integration classes at test compilation and does not need Docker. The separate identity-contract fixture remains useful for testing the transaction manager independently; the new V3 fixture is the one that applies the real project migrations. Neither fixture verifies Supabase-specific roles, its `rls_auto_enable()` event trigger, Data API exposure, production credentials, or production behavior.

## Configuration notes

See `.env.example` and [environment setup](ENVIRONMENT_SETUP.md). Production should set `APP_ENV=prod`, `SESSION_COOKIE_SECURE=true`, HTTPS `APP_ORIGIN` and `API_ORIGIN`, and separate DDL migration credentials. `application-prod.yml` is loaded only when the Spring `prod` profile is active; `APP_ENV` is a separate Barnali setting. If using this profile, set `SPRING_PROFILES_ACTIVE=prod` as well.

## Troubleshooting

- Empty/error public pages commonly mean API/database is not reachable; no sample content is inserted.
- Ordinary API startup requires runtime database access and the HMAC key; it does not run Flyway or owner bootstrap. Migration and first-owner provisioning are separate explicit operations.
- This host's `node` on `PATH` is v22.16.0, below the declared engine range. A bundled Node v24.19.0 is already available and was used for checks; use a runtime matching `apps/web/package.json` rather than changing dependency versions just to suppress the warning.
- CORS uses exact `APP_ORIGIN`; avoid a trailing path or wildcard origin with credentials.
- Keep connection URLs and secrets out of issue reports and logs.
