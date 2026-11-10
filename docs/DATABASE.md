# Database design and migration guide

For the full 14-table and source-query review, see the [database access-control matrix](DATABASE_ACCESS_CONTROL_MATRIX.md).

## Connection model

Spring and the Nuxt frontend use the Spring API; source inspection found no Supabase client or Data API requests in the web app. The API connects to PostgreSQL over JDBC. A read-only diagnostic on 2026-10-23 confirmed the approved Supabase Session Pooler target, `sslmode=require`, and database-reported SSL. No database writes were performed.

Configure `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for the runtime connection, and all three `MIGRATION_DATABASE_*` values for the isolated Flyway command. Migration credentials are mandatory for both diagnostics and apply; there is no fallback to runtime credentials. Set `BARNALI_EXPECTED_SUPABASE_PROJECT_REF` independently from the Supabase Dashboard project URL or settings; preflight compares both Session Pooler role suffixes against that operator-supplied value. Matching role suffixes alone are not proof of the intended project. Both JDBC URLs must identify the approved Session Pooler host, port, database and SSL mode, and role names must remain distinct. See [environment setup](ENVIRONMENT_SETUP.md).

The migrations do not create database roles. Use three distinct principals: barnali_migrator owns barnali and performs DDL only there; barnali_runtime has only reviewed application grants and no DDL/ownership; barnali_provisioner has only the owner readiness/provision EXECUTE grants after V3. None may have SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS, or inherited role memberships. The migration role must not retain database-wide CREATE or CREATE on other schemas. The diagnostic is evidence, not a grant script.

Do not use BYPASSRLS for any application role. The existing event trigger enables RLS on tables created by V1/V2; V3 now defines source policies and narrow functions for all 14 tables, but has not been applied or validated. Until V3 is applied and tested, a non-BYPASSRLS runtime role will be denied by RLS despite SQL grants.

The API source now provides `BarnaliJdbcTransactionManager`. When Spring starts a transaction, it first binds its JDBC connection, then the manager uses that same connection for `SELECT set_config('barnali.user_id', ?, true)`. Hikari initializes each physical connection with an empty `barnali.user_id` baseline; PostgreSQL restores that baseline after commit/rollback. The value comes only from an authenticated `AppPrincipal` in Spring Security. No client-supplied user ID or admin role is used. If there is no app principal, the transaction-local value is empty. Public registration, login/authentication lookup, password recovery/reset, and invitation acceptance suppress any existing session principal while they run. Identity-setup failure causes rollback and connection unbinding. The source also puts transactions around protected member/admin reads and current-user checks. The custom setting is a trusted-backend boundary: anyone with the runtime database credential or arbitrary SQL execution as that role could set it to another UUID. The bridge does not replace parameterized SQL, server-side authorization, or secret protection.

V3 policies use barnali.current_actor_id(), which validates the transaction-local setting and fails closed when it is empty or malformed. Member policies additionally check the active account. Administrative predicates derive permissions from active barnali.admin_accounts and mirror PermissionMatrix. Public reads are limited to published content in active public rooms; counts use restricted views/functions. These policies are source-reviewed only and need real PostgreSQL tests.

Authentication and bootstrap use empty transaction identity and narrow routines. Login calls auth_principal_by_email to return a credential projection only to trusted Spring code; registration, password changes/reset, login throttling, invitation acceptance, reporting, analytics, and owner provisioning use fixed-search-path functions. Normal public/member/admin CRUD remains direct parameterized SQL behind RLS and explicit grants. V3 defines these source boundaries but is not applied or PostgreSQL-validated.

### Pre-authentication and bootstrap inventory

| Flow | Current database work | Required restricted path before RLS use |
| --- | --- | --- |
| Login | AppUserDetailsService calls auth_principal_by_email for the matching email; rate limits use auth_login_limits; successful login updates account/activity/admin last-used state. | Trusted API receives the matched credential projection for Spring password verification; the runtime cannot directly select password_hash. Rate-limit functions expose only blocked/record/clear/audit/prune outcomes. |
| Registration | AccountService.register validates, BCrypt-hashes, and calls register_user; rate limits use dedicated functions. | register_user validates normalized fields and accepts only the API-produced BCrypt hash. RLS does not permit direct users INSERT. |
| Password recovery/reset | PasswordRecoveryService calls issue_password_reset, invalidate_password_reset_delivery, and complete_password_reset; current-password change uses current_password_hash and change_current_password. | Functions store only SHA-256 token hashes, consume tokens once, and return no token/email/user identity. Current-password verification remains in trusted Java. |
| Invitation acceptance | AdminTeamService.accept calls invitation_by_token, auth_principal_by_email for existing account password verification, then accept_invitation in the same transaction. | Token hash is never returned; existing users must prove their password in Java; the database function atomically locks/consumes the invitation and creates or associates only a delegated admin role. |
| Owner bootstrap | The isolated runner uses OWNER_PROVISIONER_DATABASE_*; read-only readiness calls owner_bootstrap_readiness, followed by provision_initial_owner only after approval. | barnali_provisioner has no table/sequence rights and receives EXECUTE on only these two functions. V3 atomically locks and consumes the guard with OWNER creation; deleting an OWNER later does not reopen it. |

Public community reads cover only active public rooms and published discussions/replies plus safe profile fields. Authenticated member operations include `myRooms`, joining a room, creating discussions/replies, voting, reporting, and activity writes; their actor must match the transaction identity. Admin reads/writes include analytics, user moderation, report resolution, room management, audit reads, and delegated-admin management; policies must derive an active role from `admin_accounts` and retain the Java permission matrix. Count queries over votes/memberships and joins to `users` need dedicated review or safe views so row filters do not silently distort counts or reveal password hashes. This inventory defines review targets, not permission grants.

The read-only migration preflight reports role memberships held by both connected principals, effective database/schema/relation/column/sequence/function privileges across non-system schemas, and ACL entries explicitly granted to PostgreSQL `PUBLIC`. The current fail-closed policy blocks migration readiness if either connected principal is a member of another role; it does not report every principal that may be a member of the application role. After schema ownership is provisioned, separately verify that the temporary `postgres`-to-migrator `SET ROLE` permission has been revoked. Resolve unexpected memberships through the approved role-management interface and rerun diagnostics. The isolated first-owner preflight checks a narrow additional runtime privilege set: schema `USAGE`; `SELECT` and `UPDATE` on `admin_bootstrap_guard`; `SELECT` and `INSERT` on `admin_accounts` and `users`; `INSERT` on `admin_audit_events`; `USAGE` on that table's identity sequence; and `SELECT` on `flyway_schema_history`. These grants do not make writes work under RLS: appropriate policies and an owner-provisioning RLS design are still required. This is only the bootstrap requirement, not a complete grant plan for normal API routes. The diagnostic inventories effective access but never issues grants.

## Private schema plan

Use the dedicated schema `barnali`. The checked-in application SQL, owner-bootstrap queries, and V1/V2 object declarations use unqualified names. Runtime Hikari is configured to set its connection schema to `barnali`; Flyway uses `schemas=barnali` and `default-schema=barnali`. Flyway is disabled in ordinary API startup and is enabled only by the isolated migration command. The migration runner's history and expected-object queries are schema-qualified to `barnali`. The public inventory separately inspects `public.rls_auto_enable()` and other public objects.

Prior read-only catalog inspection found `public.rls_auto_enable()` is a `SECURITY DEFINER` event-trigger function, owned by `postgres` and not extension-owned. The enabled event trigger fires on `CREATE TABLE`; the inspected body dynamically enables RLS on new tables whose DDL event has a non-null schema name, including `barnali`. It does not create policies or change default privileges. Do not execute, replace, drop, or alter this database-managed function. Its privileged dynamic DDL is a migration risk if its metadata or scope changes; its provenance and deployment history remain unestablished. Recheck its metadata during read-only preflight.

Keep `barnali` out of Supabase API settings' **Exposed schemas**. Supabase custom schemas require explicit exposure and grants to be reachable through the Data API; this application does not need that path. Do not add `barnali` to the exposed list or grant Data API roles access.

The last reported preflight found `barnali` absent, no BARNALI tables or Flyway history, and V1/V2 pending. That is historical read-only evidence, not a current check. Supabase documents role creation with `CREATE ROLE`, login roles, and a Dashboard role manager; the Dashboard documentation does not specify whether its current UI can assign schema ownership. Use the UI to create roles only if it exposes the required restricted attributes and password controls. If creating a role without setting its password, PostgreSQL's interactive `psql` `\\password role_name` prompt can set it without putting cleartext in query history or server logs. Generate separate credentials in a password manager and store them only in the approved secret store. Do not put a password in a SQL literal, migration, script, command argument, or source control.

Schema ownership has separate PostgreSQL requirements. `CREATE SCHEMA` needs the active SQL Editor role to have database-level `CREATE` on `postgres`; assigning another role as owner requires the operator to be able to `SET ROLE` to that owner. The Supabase SQL Editor runs as `postgres`, which is an administrative role but not a PostgreSQL superuser. The confirmed operator-provided state says this project's `postgres` has database `CREATE` and role-management privileges. Supabase's guidance documents temporarily granting the newly created role to `postgres` with `SET`, performing the ownership operation, then revoking that temporary membership. This lets `postgres` create the private schema as owner `barnali_migrator` without granting database-wide `CREATE` to the migrator. Role attribute `CREATEDB` (permission to create databases) is different from database ACL `CREATE` (permission to create schemas). See [Supabase Postgres Roles](https://supabase.com/docs/guides/database/postgres/roles), [Supabase role-membership and ownership guidance](https://supabase.com/kb/troubleshooting/cant-revoke-a-role-membership-granted-by-supabase_admin), and [PostgreSQL CREATE SCHEMA requirements](https://www.postgresql.org/docs/17/sql-createschema.html).

The following is a documented provisioning pattern, not an instruction executed by BARNALI. After roles exist, use the Supabase SQL Editor as `postgres` and run the statements together in one explicit transaction. They temporarily add only `SET` access for the administrative `postgres` session, then remove that grant. If any statement fails, roll back the transaction; do not leave the temporary membership in place. PostgreSQL-version support and the project's effective `postgres` role options must be confirmed read-only before running it:

```sql
BEGIN;
GRANT barnali_migrator TO postgres WITH SET TRUE, INHERIT FALSE;
CREATE SCHEMA barnali AUTHORIZATION barnali_migrator;
REVOKE barnali_migrator FROM postgres;
COMMIT;
```

Do not grant database-wide `CREATE` to `barnali_migrator` for this path. Afterward, read-only verification must show that `barnali` is owned by `barnali_migrator`, the migrator has no database-wide `CREATE`, and `postgres` cannot `SET ROLE` to the migrator. On PostgreSQL 16 and later, Supabase may retain a platform-managed `postgres` membership with `SET` and `INHERIT` disabled; do not attempt to remove a membership granted by `supabase_admin`. If the temporary grant or revoke is rejected, the server version is older, or the final `pg_has_role('postgres','barnali_migrator','SET')` check is true, stop and ask the project DBA/Supabase support for a reviewed path. No schema or role operation has been run in this work.

### Read-only role and schema checks

Before any role or schema write, open **Supabase Dashboard → SQL Editor** and run this read-only check as the displayed `postgres` connection. It reports the server version and current operator's relevant attributes without returning credentials:

```sql
SELECT current_database() AS database_name,
       current_user AS editor_role,
       current_setting('server_version') AS server_version,
       has_database_privilege(current_user, current_database(), 'CREATE') AS can_create_schema,
       (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS is_superuser,
       (SELECT rolcreaterole FROM pg_roles WHERE rolname = current_user) AS has_createrole_attribute;
```

If the server is older than PostgreSQL 16, stop before using the temporary membership pattern above; its `SET` and `INHERIT` membership options require a version that supports them. After an operator creates the application login roles through the supported Supabase role interface, inspect their attributes and every membership edge:

```sql
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolinherit
FROM pg_catalog.pg_roles
WHERE rolname IN ('barnali_runtime', 'barnali_migrator')
ORDER BY rolname;

SELECT grantor.rolname AS grantor, granted_role.rolname AS granted_role,
       member_role.rolname AS member_role, membership.admin_option
FROM pg_catalog.pg_auth_members membership
JOIN pg_catalog.pg_roles granted_role ON granted_role.oid = membership.roleid
JOIN pg_catalog.pg_roles member_role ON member_role.oid = membership.member
JOIN pg_catalog.pg_roles grantor ON grantor.oid = membership.grantor
WHERE granted_role.rolname IN ('barnali_runtime', 'barnali_migrator')
   OR member_role.rolname IN ('barnali_runtime', 'barnali_migrator')
ORDER BY granted_role.rolname, member_role.rolname, grantor.rolname;
```

On PostgreSQL 16+, add `membership.inherit_option` and `membership.set_option` to the membership query. Inspect the membership direction and grantor; do not blindly revoke a Supabase-managed membership. The application roles must not inherit privileges from other roles. After the temporary schema-owner step is separately approved, verify its effects and unexpected schema `CREATE` privileges with these read-only queries:

```sql
SELECT n.nspname AS schema_name, pg_get_userbyid(n.nspowner) AS schema_owner,
       has_database_privilege('barnali_migrator', current_database(), 'CREATE') AS migrator_database_create,
       has_database_privilege('barnali_runtime', current_database(), 'CREATE') AS runtime_database_create,
       has_schema_privilege('barnali_migrator', n.oid, 'USAGE') AS migrator_usage,
       has_schema_privilege('barnali_migrator', n.oid, 'CREATE') AS migrator_schema_create,
       has_schema_privilege('barnali_runtime', n.oid, 'USAGE') AS runtime_usage,
       has_schema_privilege('barnali_runtime', n.oid, 'CREATE') AS runtime_schema_create,
       pg_has_role('postgres', 'barnali_migrator', 'SET') AS postgres_can_set_migrator
FROM pg_catalog.pg_namespace n WHERE n.nspname = 'barnali';

SELECT n.nspname AS schema_with_unexpected_create
FROM pg_catalog.pg_namespace n
WHERE n.nspname <> 'barnali' AND n.nspname <> 'information_schema'
  AND n.nspname !~ '^pg_'
  AND (has_schema_privilege('barnali_migrator', n.oid, 'CREATE')
       OR has_schema_privilege('barnali_runtime', n.oid, 'CREATE'))
ORDER BY n.nspname;
```

The first schema query returns no row while `barnali` is absent, which does not prove ownership; the second should return no rows. These catalog queries supplement, but do not replace, the independent project-reference, SSL, `PUBLIC`-ACL, and effective table/sequence/function privilege checks. Once the settings are configured, `.\scripts\diagnose-migrations.ps1` runs the application's read-only preflight. Do not run `.\scripts\migrate-api.ps1 -Apply` as part of these checks.

Before migrations, run `.\scripts\diagnose-migrations.ps1` and verify the independent project reference, database identity, SSL, zero memberships, role separation, schema ownership, no database-wide `CREATE` on the migrator, and effective privileges. After a separate migration approval and successful preflight, `.\scripts\migrate-api.ps1 -Apply` applies V1/V2/V3. Do not start the API or bootstrap an owner until the additive V3 policies and functions are applied and independently verified with PostgreSQL. Scripts import only required database variable names into process memory and never print their values. No schema or migration was created/applied in this work.

## Current tables

| Table | Purpose |
| --- | --- |
| `users` | Member/admin identity, password hash, status, verification timestamp, auth version, and timestamps. |
| `rooms`, `room_memberships` | Public/active topic rooms and membership relationship. |
| `discussions`, `replies` | Published/hidden/removed community content. |
| `discussion_votes` | One -1/+1 vote per user and discussion. |
| `reports` | Moderation reports against discussion, reply, or user IDs. |
| `user_activity_events` | Minimal event type and timestamp for login, room join, discussion, reply, and vote. |
| `admin_accounts`, `admin_bootstrap_guard`, `admin_invitations` | Fixed admin roles, serialized owner bootstrap, hashed expiring invitations. |
| `admin_audit_events` | Append-only administrative audit history. |
| `auth_login_limits` | HMAC-keyed failure buckets and throttling. |
| `password_reset_tokens` | Hashed, expiring, single-use recovery tokens. |

There are no bookmark, follow, block, notification, private-message, or real-time event tables in the current schema.

## Migrations

1. `V1__barnali_community_and_admin.sql` creates 14 tables: `users`, `rooms`, `room_memberships`, `discussions`, `replies`, `discussion_votes`, `reports`, `user_activity_events`, `admin_accounts`, `admin_bootstrap_guard`, `admin_invitations`, `admin_audit_events`, `auth_login_limits`, and `password_reset_tokens`. It creates 22 named indexes: `users_created_at_idx`, `users_status_idx`, `rooms_active_public_idx`, `room_memberships_user_idx`, `discussions_public_created_idx`, `discussions_room_created_idx`, `discussions_author_created_idx`, `discussions_search_idx`, `replies_discussion_created_idx`, `replies_author_created_idx`, `reports_status_created_idx`, `reports_target_idx`, `activity_user_time_idx`, `activity_time_type_idx`, `admin_accounts_single_owner_idx`, `admin_accounts_active_role_idx`, `admin_invitations_email_created_idx`, `admin_audit_created_idx`, `admin_audit_actor_idx`, `admin_audit_action_idx`, `auth_login_limits_updated_idx`, and `password_reset_tokens_expiry_idx`. It also creates `barnali_reject_audit_mutation()` and trigger `admin_audit_events_no_update`, then inserts the sole seed row `admin_bootstrap_guard(id=1)`. Table definitions include primary/unique/check/foreign-key constraints; foreign-key delete actions govern future deletes and do not delete rows during migration.
2. `V2__rate_limit_audit_and_report_guard.sql` adds `auth_login_limits.last_audit_at TIMESTAMPTZ` and creates partial unique index `reports_one_pending_report_per_reporter_target_idx` on `(reporter_id, target_type, target_id)` where `status='PENDING'`.
3. `V3__row_security_and_scoped_auth_operations.sql` adds `admin_bootstrap_guard.consumed_at`, enables RLS on all 14 tables, creates scoped policies, fixed-search-path authentication/rate-limit/recovery/invitation/report/analytics/bootstrap functions, five access-limited views, and explicit schema/table/sequence/function ACLs. It adds no seed rows or DROP/TRUNCATE operation. V1 and V2 remain unchanged.

Flyway validates applied migrations and disables `clean`. None of V1/V2/V3 drops or truncates objects. V1 inserts the single bootstrap guard seed; V3 adds schema metadata, policies, functions, views, and grants but no seed rows. V3's one-shot owner function performs the guarded owner writes only when the separately approved bootstrap command is run. The migrations use PostgreSQL UUID generation, JSONB, identity columns, GIN/full-text search, and PL/pgSQL; they do not create extensions. Back up before deployment. Migrations are forward-only; use a reviewed compensating migration or restore procedure instead of editing an applied migration.

### Migration-only command

For a read-only migration preflight, run .\scripts\diagnose-migrations.ps1 from the workspace root. It imports only runtime and migration database settings plus the non-secret expected project reference into process memory. It verifies the pooler target and independent project identity, live database name and TLS for both connections, role separation and memberships/inherited privileges, effective database and all non-system schema/relation/column/sequence/function privileges, explicit `PUBLIC` ACLs, schema ownership/usage/DDL scope, runtime object privileges, search path, Flyway history and expected V1/V2/V3 objects, including V3 security functions, views, policies and bootstrap guard column. It separately inventories public objects and inspects public.rls_auto_enable() without printing its definition. When barnali is absent, validation is SKIPPED_SCHEMA_ABSENT; pending status remains separate. When the schema exists, pending-only validation is PASSED_WITH_PENDING, while checksum, missing-migration, and failed-migration errors remain failures. The diagnostic never calls Flyway.migrate(), starts the full API, or registers owner bootstrap. Catalog and data checks use explicitly read-only transactions. Role and privilege output is evidence for review, not a grant plan.

Ordinary API startup has Flyway disabled and does not register owner bootstrap. From the workspace root, .\scripts\migrate-api.ps1 is informational only. After separate explicit approval and schema provisioning, .\scripts\migrate-api.ps1 -Apply asks for APPLY BARNALI PRIVATE SCHEMA MIGRATIONS. It imports only runtime and migration database settings, requires both credentials explicitly, checks the approved target and schema ownership/DDL boundaries, and uses the migration principal only for Flyway. It pins Flyway to barnali, keeps clean disabled, validation enabled, schema creation disabled, and baseline disabled. After success it reads runtime search path, table grants and RLS flags; this post-check does not create policies or roll back an already committed migration.

The owner-bootstrap runner now connects with separate OWNER_PROVISIONER_DATABASE_* settings, performs read-only readiness checks, BCrypt-hashes the supplied password, and invokes only owner_bootstrap_readiness() and provision_initial_owner(...). V3 grants that principal EXECUTE on those two functions and no BARNALI table or sequence rights. The function locks and consumes a persistent guard in the same transaction as owner creation; deleting the owner later does not reset it. This source design is not live-verified. Do not run owner bootstrap until V3 is applied and read-only diagnostics confirm the role, target, and function access.

No schema creation, migration, owner bootstrap, full API startup, or database record modification has occurred in this task. Rerun the diagnostic after each approved database change before proceeding to the next stage.

## Transaction identity and test boundary

The Spring identity hook is covered by offline tests for principal-only identity, anonymous-scope restoration, same Spring-bound connection usage, empty identity on a reused mock connection, commit/rollback handling, and fail-closed setup cleanup. Controller tests cover identity suppression during registration, login lookup, password recovery/reset, and invitation acceptance. These tests do not emulate PostgreSQL RLS semantics. `docker`, `psql`, and `pg_ctl` are unavailable in the current Windows environment, and this task did not contact Supabase. Before production use of V3, run the kept PostgreSQL integration profile against isolated local PostgreSQL. Verify commit/rollback cleanup, Hikari reuse, each role policy, function/view ACL behavior, identity/sequence privileges, and bootstrap using a non-owner runtime role.

## Key integrity and indexes

- Lowercase unique username/email and unique room/discussion slug constraints.
- Admin role and status check constraints; partial unique index permits only one `OWNER` role.
- Foreign keys enforce room membership, content ownership, reviewer, invitations, and activity relationships.
- Indexes cover user creation/status, active public rooms, recent public and room discussions, replies, reports by status, user activity time, admin lookup, invitations, and audit time/actor/action.
- Audit mutation trigger protects against ordinary application SQL; a database owner can alter the trigger and data.

## Analytics data boundaries

Activity starts when the event table is populated. No past activity is inferred from signup timestamps. Events do not store message bodies, IP addresses, device fingerprints, or client identifiers. Retention policy for audit and activity rows is not yet configurable.

## Verification state

Historical user-reported read-only checks before this V3 source change found database postgres, target schema barnali absent, Flyway history absent, V1/V2 pending, SSL enforced, and shared application credentials with BYPASSRLS and database CREATE. This task did not reconnect, so none of those live facts are refreshed and V3 was not included in that historical migration inventory. The source preflight requires distinct principals, schema ownership by the migration role, no broad DDL privileges, no BYPASSRLS on either role, and no role memberships. The source now contains additive V3 policies and functions to address event-trigger RLS. Live readiness remains blocked because V3 is not applied or PostgreSQL-validated, and this pass did not inspect the live Supabase roles or schema. No schema creation, migration, owner bootstrap, full API startup, or database write occurred in this task.
