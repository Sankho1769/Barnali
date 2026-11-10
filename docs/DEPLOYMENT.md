# Deployment guide

## Readiness

**Current verdict: NOT READY FOR PRODUCTION.** The source now includes additive V3 security policies/functions. No database state was inspected or changed in this pass; at the last user-confirmed setup report, the application roles and `barnali` schema did not exist and V1/V2 were not applied. Live state is not refreshed. SMTP, MFA, hosting target, backup/restore process, and monitoring are also unconfigured.

## Required configuration

Set environment values using the platform's secret manager; see `.env.example` for names. At minimum configure:

- `APP_ENV=prod`, `SESSION_COOKIE_SECURE=true`, HTTPS `APP_ORIGIN`, HTTPS `API_ORIGIN`.
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` for the runtime account.
- MIGRATION_DATABASE_URL, MIGRATION_DATABASE_USERNAME, and MIGRATION_DATABASE_PASSWORD are mandatory for the isolated migration diagnostic and apply command; the migration runner never falls back to DATABASE_* values. They must use a distinct role and project-reference suffix consistent with the runtime credentials.
- `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` is a non-secret operator-supplied value copied independently from Supabase Dashboard project settings; diagnostics compare it against both Session Pooler username suffixes.
- `APP_SECURITY_HMAC_KEY` as a random secret of 32 bytes or more.
- OWNER_EMAIL for the designated first owner. Inject OWNER_BOOTSTRAP_PASSWORD from a deployment secret manager only for the explicit one-shot provisioning command; do not store it in source, command arguments, or .env. The command removes the local process value after success.

The ordinary API process has Flyway disabled. Apply schema changes only through the isolated, explicitly approved migration command. Do not give the runtime principal DDL or schema-creation privileges merely to support migrations.
- SMTP host/credentials/from address if recovery mail or automated delivery is enabled. The current UI provides manual invitation-link handoff when SMTP is absent.
- `NUXT_PUBLIC_API_BASE` and the runtime API URL to match the actual HTTPS API deployment.

If using `application-prod.yml`, activate Spring profile with `SPRING_PROFILES_ACTIVE=prod`; `APP_ENV=prod` configures the Barnali validator but does not itself activate the Spring profile. Do not expose any database or SMTP secret to Nuxt public runtime config.

## Release sequence

1. Provision three distinct staging principals: `barnali_migrator` owns only the `barnali` schema; `barnali_runtime` has no DDL, ownership, or BYPASSRLS; `barnali_provisioner` has no BARNALI table/sequence rights and receives only the two owner routine grants. The migration preflight checks effective privileges and memberships without issuing grants.
2. Back up staging. After separate schema-creation approval, an authorized operator creates `barnali` and assigns ownership to `barnali_migrator` without database-wide CREATE. Rerun the read-only diagnostic and verify project identity, owner, role attributes/memberships, SSL, effective privileges, and that runtime/provisioner remain distinct. Then separately approve `migrate-api.ps1 -Apply` for V1/V2/V3. The existing event trigger enables RLS; V3 supplies scoped policies, but behavior must pass isolated PostgreSQL tests before API or owner bootstrap.
3. Configure HTTPS, origin/CORS, cookie, proxy, HMAC and secret-manager settings; verify startup rejection for unsafe production configuration. Do not expose database credentials through Nuxt public runtime config.
4. After V3, runtime grants, and bootstrap readiness are independently verified, inject the bootstrap password securely into the operator process, then run bootstrap-owner.ps1 -Apply and type CREATE BARNALI OWNER. This is an isolated non-web operation using the execute-only provisioner principal. Its database function atomically consumes the persistent guard with owner creation and refuses inconsistent state or an already-used email. Verify password change on first sign-in, then remove the bootstrap secret.
5. Run backend/frontend tests and end-to-end auth/RBAC/content/analytics tests against synthetic staging fixtures. Complete MFA and email verification work before real user data.
6. Configure mail, monitoring, log redaction, backups, alerting, restore drills, and incident procedures.
7. Deploy the API and Nuxt app, smoke-check public routes and protected API denial, then monitor errors and migration status.

## Rollback and recovery

Migrations are forward-only. A failed preflight before migration leaves the schema unchanged; a Flyway failure may have committed earlier versioned migrations depending on database transaction behavior, so inspect read-only history and objects before retrying. A post-migration runtime/RLS check is read-only and cannot undo a migration already committed. Roll back by deploying a compatible previous application version only if schema compatibility is confirmed; otherwise use a reviewed compensating migration or restore a verified backup. Never reset the database or edit an applied migration. Owner recovery is a privileged operator procedure and must never be done through a public endpoint or direct password edit. Test recovery in staging first.

## Unverified deployment facts

No domain, TLS termination, cloud host, CDN, secrets manager, SMTP provider, database backup, or rollback procedure has been configured or tested. The configured pooler connection, PostgreSQL authentication, `SELECT 1`, and SSL were verified read-only. V3 has not been applied or tested on PostgreSQL. Authentication, role/RLS behavior, and the owner-provisioner function remain unverified; this project is not ready for production.
