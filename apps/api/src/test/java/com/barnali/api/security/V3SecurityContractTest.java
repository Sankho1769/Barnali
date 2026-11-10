package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.barnali.api.admin.AdminRole;
import com.barnali.api.admin.PermissionMatrix;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Offline source-contract checks. These do not prove PostgreSQL RLS behavior. */
class V3SecurityContractTest {
    private static final Set<String> TABLES = Set.of(
            "users", "rooms", "room_memberships", "discussions", "replies", "discussion_votes", "reports",
            "user_activity_events", "admin_accounts", "admin_bootstrap_guard", "admin_invitations",
            "admin_audit_events", "auth_login_limits", "password_reset_tokens");

    private final String migration = resource("db/migration/V3__row_security_and_scoped_auth_operations.sql");

    @Test
    void everyV1TableHasRlsAndAtLeastOneExplicitPolicy() {
        Set<String> secured = Pattern.compile("ALTER TABLE (\\w+) ENABLE ROW LEVEL SECURITY;")
                .matcher(migration).results().map(match -> match.group(1)).collect(Collectors.toSet());
        Set<String> policyTables = Pattern.compile("CREATE POLICY \\w+ ON (\\w+)")
                .matcher(migration).results().map(match -> match.group(1)).collect(Collectors.toSet());

        assertThat(secured).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(policyTables).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(migration).doesNotContain("FORCE ROW LEVEL SECURITY", "DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void sqlAdminPermissionMapMatchesTheJavaAuthorizationMatrix() {
        Matcher matcher = Pattern.compile("WHEN '([^']+)' THEN p_permission IN \\(([^)]*)\\)")
                .matcher(migration);
        int found = 0;
        while (matcher.find()) {
            AdminRole role = AdminRole.valueOf(matcher.group(1));
            Set<String> sqlPermissions = Pattern.compile("'([A-Z_]+)'")
                    .matcher(matcher.group(2)).results().map(match -> match.group(1)).collect(Collectors.toSet());
            assertThat(sqlPermissions).as("permissions for %s", role)
                    .isEqualTo(new HashSet<>(PermissionMatrix.permissions(role)));
            found++;
        }
        assertThat(found).isEqualTo(EnumSet.allOf(AdminRole.class).size());
    }

    @Test
    void everySecurityDefinerRoutineHasAnExplicitFixedSearchPath() {
        Matcher matcher = Pattern.compile("(?s)CREATE FUNCTION (\\w+)\\(.*?AS \\$\\$(.*?)\\$\\$;")
                .matcher(migration);
        int definerCount = 0;
        while (matcher.find()) {
            String statement = matcher.group();
            if (!statement.contains("SECURITY DEFINER")) continue;
            assertThat(statement).as("SECURITY DEFINER function %s", matcher.group(1))
                    .contains("SET search_path = pg_catalog, barnali, pg_temp");
            definerCount++;
        }
        assertThat(definerCount).isGreaterThanOrEqualTo(20);
    }

    @Test
    void sensitiveAuthenticationAndBootstrapTablesHaveNoRuntimeTableGrants() {
        assertThat(migration).contains("REVOKE ALL ON ALL TABLES IN SCHEMA barnali FROM barnali_runtime,barnali_provisioner");
        assertThat(migration).doesNotMatch("(?is)GRANT[^;]*\\bON\\s+(admin_bootstrap_guard|auth_login_limits|password_reset_tokens)\\s+TO\\s+barnali_runtime");
        assertThat(migration).doesNotMatch("(?is)GRANT[^;]*\\b(password_hash|token_hash)\\b[^;]*TO\\s+barnali_runtime");
        assertThat(migration).contains(
                "GRANT EXECUTE ON FUNCTION owner_bootstrap_readiness(),provision_initial_owner(TEXT,TEXT) TO barnali_provisioner");
        assertThat(migration).doesNotMatch("(?is)GRANT[^;]*\\b(owner_bootstrap_readiness|provision_initial_owner)\\b[^;]*TO\\s+barnali_runtime");
        assertThat(migration).contains("FROM PUBLIC");
    }

    @Test
    void v1AuditTriggerFunctionDoesNotKeepTheDefaultPublicExecuteGrant() {
        String v1 = resource("db/migration/V1__barnali_community_and_admin.sql");
        assertThat(v1).contains("CREATE FUNCTION barnali_reject_audit_mutation()");
        assertThat(migration).contains(
                "REVOKE ALL ON FUNCTION barnali.barnali_reject_audit_mutation() FROM PUBLIC");
    }

    @Test
    void auditReadAloneDoesNotGrantGeneralUserRows() {
        String userPolicy = migration.substring(migration.indexOf("CREATE POLICY users_select_scoped"),
                migration.indexOf("CREATE POLICY users_update_moderation"));
        String auditView = migration.substring(migration.indexOf("CREATE VIEW admin_audit_rows"),
                migration.indexOf("REVOKE ALL ON TABLE"));

        assertThat(userPolicy).doesNotContain("actor_has_permission('AUDIT_READ')");
        assertThat(auditView).contains("actor_has_permission('AUDIT_READ')", "u.display_name AS actor_name");
        assertThat(migration).contains("GRANT SELECT ON public_room_summaries,admin_user_rows,admin_team_rows,admin_room_rows,admin_audit_rows TO barnali_runtime");
    }

    @Test
    void auditLogRepositoryUsesThePermissionGatedAuditView() throws IOException {
        String adminService = Files.readString(Path.of("src/main/java/com/barnali/api/admin/AdminService.java"));
        assertThat(adminService).contains("FROM barnali.admin_audit_rows e")
                .doesNotContain("FROM admin_audit_events e LEFT JOIN users");
    }

    @Test
    void ownerProvisioningUsesPersistentAtomicGuardAndRuntimeDoesNotOwnBootstrapPath() {
        assertThat(migration).contains("ALTER TABLE admin_bootstrap_guard ADD COLUMN consumed_at TIMESTAMPTZ");
        String provision = function("provision_initial_owner");
        assertThat(provision).contains("barnali.owner_bootstrap_readiness() <> 'READY'", "FOR UPDATE",
                "UPDATE barnali.admin_bootstrap_guard SET consumed_at", "INSERT INTO barnali.admin_accounts");
        assertThat(provision).doesNotContain("DELETE FROM barnali.admin_bootstrap_guard");
        assertThat(migration).contains("OR guard_consumed IS NOT NULL", "IF owner_count<>0 THEN RETURN 'INCONSISTENT'");
        assertThat(migration).contains("GRANT EXECUTE ON FUNCTION owner_bootstrap_readiness(),provision_initial_owner(TEXT,TEXT) TO barnali_provisioner");
    }

    @Test
    void migrationRoleGuardRejectsDatabaseWideAndOutOfSchemaDdl() {
        String roleGuard = migration.substring(migration.indexOf("DO $roles$"), migration.indexOf("$roles$;"));
        assertThat(roleGuard).contains("has_database_privilege(migrator_oid,current_database(),'CREATE')",
                "has_schema_privilege(migrator_oid,other_schema.oid,'CREATE')")
                .contains("other_schema.nspname<>'barnali'");
    }

    @Test
    void preAuthenticationFunctionsDoNotReturnRecoveryOrInvitationTokenHashes() {
        String recovery = function("complete_password_reset");
        String invitation = function("invitation_by_token");
        assertThat(recovery).doesNotContain("RETURNS TABLE", "RETURN token_hash", "RETURN p_token_hash");
        assertThat(invitation).contains("RETURNS TABLE(email TEXT,role TEXT)")
                .contains("RETURN QUERY SELECT i.email,i.role")
                .doesNotContain("RETURNS TABLE(id", "RETURN QUERY SELECT i.id", "RETURN QUERY SELECT i.email,i.role,i.invited_by");
        assertThat(migration).contains("CREATE FUNCTION auth_principal_by_email")
                .contains("CREATE FUNCTION issue_password_reset")
                .contains("CREATE FUNCTION accept_invitation");
    }

    @Test
    void passwordChangesAndResetsInvalidateEveryOutstandingRecoveryToken() {
        String issue = function("issue_password_reset");
        String change = function("change_current_password");
        String complete = function("complete_password_reset");

        assertThat(issue).contains("FOR UPDATE",
                "UPDATE barnali.password_reset_tokens SET consumed_at=pg_catalog.now()",
                "WHERE user_id=target_id AND consumed_at IS NULL");
        assertThat(change).contains("WHERE user_id=actor_id AND consumed_at IS NULL");
        assertThat(complete).contains("PERFORM 1 FROM barnali.users WHERE id=target_id FOR UPDATE",
                "WHERE user_id=target_id AND consumed_at IS NULL");
        int userLock = complete.indexOf("PERFORM 1 FROM barnali.users WHERE id=target_id FOR UPDATE");
        int tokenRelock = complete.indexOf("SELECT user_id INTO target_id FROM barnali.password_reset_tokens",
                complete.indexOf("Re-check after waiting for the user lock"));
        assertThat(userLock).isGreaterThanOrEqualTo(0).isLessThan(tokenRelock);
        assertThat(complete.substring(tokenRelock)).startsWith("SELECT user_id INTO target_id FROM barnali.password_reset_tokens")
                .contains("FOR UPDATE");
    }

    @Test
    void reportAndModerationBoundariesMatchTheCurrentServiceOperations() {
        assertThat(migration).contains("p_reason NOT IN ('SPAM','HARASSMENT','MISINFORMATION','OFF_TOPIC','OTHER')")
                .contains("CREATE POLICY discussions_update_moderator")
                .contains("WITH CHECK (actor_has_permission('REPORT_RESOLVE') AND status='HIDDEN')")
                .contains("CREATE POLICY replies_update_moderator")
                .contains("ON CONFLICT (reporter_id,target_type,target_id) WHERE status='PENDING' DO NOTHING");
    }

    @Test
    void rowPoliciesRepeatSelfAndCreatorRestrictionsAtTheDatabaseBoundary() {
        assertThat(migration).contains("id<>current_actor_id() AND actor_has_permission('USER_SUSPEND')")
                .contains("id<>current_actor_id() AND actor_has_permission('ADMIN_TEAM_MANAGE')")
                .contains("actor_has_permission('ROOM_MANAGE') AND created_by=current_actor_id()");
    }

    @Test
    void publicReplyPolicyRequiresAnActiveAuthorLikeThePublicDiscussionQueries() {
        String replyPolicy = migration.substring(migration.indexOf("CREATE POLICY replies_select_visible"),
                migration.indexOf("CREATE POLICY replies_insert_member"));
        assertThat(replyPolicy).contains("reply_author.id=replies.author_id AND reply_author.status='ACTIVE'");
    }

    @Test
    void readOnlyDiagnosticsKnowV3AndInspectItsSecurityObjects() throws Exception {
        String diagnostics = Files.readString(Path.of("src/main/java/com/barnali/api/migration/MigrationOnlyApplication.java"));
        assertThat(diagnostics).contains("\"3\", \"row security and scoped auth operations\"")
                .contains("WHERE version IN ('1', '2', '3')")
                .contains("V3_FUNCTIONS", "V3_VIEWS", "admin_audit_rows", "V3_POLICY_COUNTS", "reportV3SchemaObjects(connection)");
    }

    private String function(String name) {
        Matcher matcher = Pattern.compile("(?s)CREATE FUNCTION " + Pattern.quote(name) + "\\(.*?\\$\\$(.*?)\\$\\$;")
                .matcher(migration);
        assertThat(matcher.find()).as("function %s exists", name).isTrue();
        return matcher.group();
    }

    private static String resource(String name) {
        try (InputStream stream = V3SecurityContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("Missing test resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test resource: " + name, exception);
        }
    }
}
