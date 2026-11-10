package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabasePrivilegeDiagnosticsTest {
    @Test
    void catalogQueriesAreFixedReadOnlyQueries() {
        assertThat(DatabasePrivilegeDiagnostics.catalogQueriesForTesting()).isNotEmpty().allSatisfy(sql -> {
            String query = sql.replaceFirst("(?s)^\\s*/\\*.*?\\*/\\s*", "").stripLeading().toUpperCase();
            assertThat(query.startsWith("SELECT") || query.startsWith("WITH")).isTrue();
            assertThat(sql).doesNotContain(";");
            assertThat(query).doesNotContainPattern("(?s)(?:^|\\s)(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE)\\s");
        });
    }

    @Test
    void inventoriesInheritedMembershipPublicAndEffectivePrivilegesAcrossSchemas() {
        String memberships = DatabasePrivilegeDiagnostics.ROLE_MEMBERSHIPS_SQL;
        String schema = DatabasePrivilegeDiagnostics.SCHEMA_PRIVILEGES_SQL;
        String publicAcls = DatabasePrivilegeDiagnostics.PUBLIC_ACL_SQL;
        String tables = DatabasePrivilegeDiagnostics.TABLE_PRIVILEGES_SQL;
        String sequences = DatabasePrivilegeDiagnostics.SEQUENCE_PRIVILEGES_SQL;
        String routines = DatabasePrivilegeDiagnostics.ROUTINE_PRIVILEGES_SQL;

        assertThat(memberships).contains("pg_has_role(current_user", "'MEMBER'", "'USAGE'");
        assertThat(schema).contains("pg_namespace", "has_schema_privilege(current_user", "nspname");
        assertThat(tables).contains("has_table_privilege(current_user", "has_any_column_privilege(current_user")
                .doesNotContain("nspname = 'barnali'");
        assertThat(sequences).contains("has_sequence_privilege(current_user").doesNotContain("nspname = 'barnali'");
        assertThat(routines).contains("has_function_privilege(current_user").doesNotContain("nspname = 'barnali'");
        assertThat(publicAcls).contains("acl.grantee = 0", "aclexplode");
    }

    @Test
    void membershipSummaryDistinguishesMembershipsFromInheritedPrivileges() {
        assertThat(new DatabasePrivilegeDiagnostics.RoleMembershipSummary(0, 0).hasNoMemberships()).isTrue();
        assertThat(new DatabasePrivilegeDiagnostics.RoleMembershipSummary(1, 0).hasNoMemberships()).isFalse();
        assertThat(new DatabasePrivilegeDiagnostics.RoleMembershipSummary(1, 1).hasNoMemberships()).isFalse();
    }
}
