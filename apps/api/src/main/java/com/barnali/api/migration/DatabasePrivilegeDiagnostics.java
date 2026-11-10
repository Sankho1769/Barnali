package com.barnali.api.migration;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Fixed, read-only catalog inventory for effective privileges of a connected principal. */
final class DatabasePrivilegeDiagnostics {
    static final String ROLE_MEMBERSHIPS_SQL = """
            /* barnali_role_memberships */
            SELECT granted_role.rolname::text,
                   pg_catalog.pg_has_role(current_user, granted_role.oid, 'MEMBER'),
                   pg_catalog.pg_has_role(current_user, granted_role.oid, 'USAGE')
            FROM pg_catalog.pg_roles granted_role
            WHERE granted_role.rolname <> current_user
              AND pg_catalog.pg_has_role(current_user, granted_role.oid, 'MEMBER')
            ORDER BY granted_role.rolname
            """;
    static final String DATABASE_PRIVILEGES_SQL = """
            /* barnali_database_effective_privileges */
            SELECT database_row.datname::text,
                   pg_catalog.has_database_privilege(current_user, database_row.oid, 'CONNECT'),
                   pg_catalog.has_database_privilege(current_user, database_row.oid, 'CREATE'),
                   pg_catalog.has_database_privilege(current_user, database_row.oid, 'TEMPORARY')
            FROM pg_catalog.pg_database database_row
            WHERE database_row.datname = current_database()
            """;
    static final String SCHEMA_PRIVILEGES_SQL = """
            /* barnali_schema_effective_privileges */
            SELECT schema_row.nspname::text,
                   pg_catalog.has_schema_privilege(current_user, schema_row.oid, 'USAGE'),
                   pg_catalog.has_schema_privilege(current_user, schema_row.oid, 'CREATE')
            FROM pg_catalog.pg_namespace schema_row
            WHERE schema_row.nspname <> 'information_schema'
              AND schema_row.nspname !~ '^pg_'
            ORDER BY schema_row.nspname
            """;
    static final String TABLE_PRIVILEGES_SQL = """
            /* barnali_relation_effective_privileges */
            SELECT schema_row.nspname::text, relation_row.relname::text,
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'SELECT'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'INSERT'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'UPDATE'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'DELETE'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'TRUNCATE'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'REFERENCES'),
                   pg_catalog.has_table_privilege(current_user, relation_row.oid, 'TRIGGER'),
                   pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'SELECT'),
                   pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'INSERT'),
                   pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'UPDATE'),
                   pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'REFERENCES')
            FROM pg_catalog.pg_class relation_row
            JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = relation_row.relnamespace
            WHERE relation_row.relkind IN ('r', 'p', 'v', 'm', 'f')
              AND schema_row.nspname <> 'information_schema'
              AND schema_row.nspname !~ '^pg_'
              AND (
                  pg_catalog.has_table_privilege(current_user, relation_row.oid, 'SELECT')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'INSERT')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'UPDATE')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'DELETE')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'TRUNCATE')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'REFERENCES')
                  OR pg_catalog.has_table_privilege(current_user, relation_row.oid, 'TRIGGER')
                  OR pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'SELECT')
                  OR pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'INSERT')
                  OR pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'UPDATE')
                  OR pg_catalog.has_any_column_privilege(current_user, relation_row.oid, 'REFERENCES')
              )
            ORDER BY schema_row.nspname, relation_row.relname
            """;
    static final String SEQUENCE_PRIVILEGES_SQL = """
            /* barnali_sequence_effective_privileges */
            SELECT schema_row.nspname::text, sequence_row.relname::text,
                   pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'USAGE'),
                   pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'SELECT'),
                   pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'UPDATE')
            FROM pg_catalog.pg_class sequence_row
            JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = sequence_row.relnamespace
            WHERE sequence_row.relkind = 'S'
              AND schema_row.nspname <> 'information_schema'
              AND schema_row.nspname !~ '^pg_'
              AND (pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'USAGE')
                   OR pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'SELECT')
                   OR pg_catalog.has_sequence_privilege(current_user, sequence_row.oid, 'UPDATE'))
            ORDER BY schema_row.nspname, sequence_row.relname
            """;
    static final String ROUTINE_PRIVILEGES_SQL = """
            /* barnali_routine_effective_privileges */
            SELECT schema_row.nspname::text,
                   (routine_row.proname || '(' || pg_catalog.pg_get_function_identity_arguments(routine_row.oid) || ')')::text
            FROM pg_catalog.pg_proc routine_row
            JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = routine_row.pronamespace
            WHERE schema_row.nspname <> 'information_schema'
              AND schema_row.nspname !~ '^pg_'
              AND pg_catalog.has_function_privilege(current_user, routine_row.oid, 'EXECUTE')
            ORDER BY schema_row.nspname, routine_row.proname,
                     pg_catalog.pg_get_function_identity_arguments(routine_row.oid)
            """;
    static final String PUBLIC_ACL_SQL = """
            /* barnali_public_acl_inventory */
            WITH public_acl(object_type, schema_name, object_name, privilege_name) AS (
                SELECT 'database', '', database_row.datname::text, acl.privilege_type::text
                FROM pg_catalog.pg_database database_row
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    database_row.datacl, pg_catalog.acldefault('d', database_row.datdba))) acl
                WHERE database_row.datname = current_database() AND acl.grantee = 0
                UNION ALL
                SELECT 'schema', schema_row.nspname::text, schema_row.nspname::text, acl.privilege_type::text
                FROM pg_catalog.pg_namespace schema_row
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    schema_row.nspacl, pg_catalog.acldefault('n', schema_row.nspowner))) acl
                WHERE schema_row.nspname <> 'information_schema'
                  AND schema_row.nspname !~ '^pg_' AND acl.grantee = 0
                UNION ALL
                SELECT CASE WHEN relation_row.relkind = 'S' THEN 'sequence' ELSE 'relation' END,
                       schema_row.nspname::text, relation_row.relname::text, acl.privilege_type::text
                FROM pg_catalog.pg_class relation_row
                JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = relation_row.relnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    relation_row.relacl,
                    pg_catalog.acldefault(CASE WHEN relation_row.relkind = 'S' THEN 'S' ELSE 'r' END,
                                          relation_row.relowner))) acl
                WHERE relation_row.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
                  AND schema_row.nspname <> 'information_schema'
                  AND schema_row.nspname !~ '^pg_' AND acl.grantee = 0
                UNION ALL
                SELECT 'routine', schema_row.nspname::text,
                       (routine_row.proname || '(' || pg_catalog.pg_get_function_identity_arguments(routine_row.oid) || ')')::text,
                       acl.privilege_type::text
                FROM pg_catalog.pg_proc routine_row
                JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = routine_row.pronamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                    routine_row.proacl, pg_catalog.acldefault('f', routine_row.proowner))) acl
                WHERE schema_row.nspname <> 'information_schema'
                  AND schema_row.nspname !~ '^pg_' AND acl.grantee = 0
                UNION ALL
                SELECT 'column', schema_row.nspname::text,
                       relation_row.relname || '.' || attribute_row.attname, acl.privilege_type::text
                FROM pg_catalog.pg_attribute attribute_row
                JOIN pg_catalog.pg_class relation_row ON relation_row.oid = attribute_row.attrelid
                JOIN pg_catalog.pg_namespace schema_row ON schema_row.oid = relation_row.relnamespace
                CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(attribute_row.attacl, '{}'::aclitem[])) acl
                WHERE attribute_row.attnum > 0 AND NOT attribute_row.attisdropped
                  AND schema_row.nspname <> 'information_schema'
                  AND schema_row.nspname !~ '^pg_' AND acl.grantee = 0
            )
            SELECT object_type, schema_name, object_name, privilege_name
            FROM public_acl
            ORDER BY object_type, schema_name, object_name, privilege_name
            """;

    private DatabasePrivilegeDiagnostics() {}

    static List<String> catalogQueriesForTesting() {
        return List.of(ROLE_MEMBERSHIPS_SQL, DATABASE_PRIVILEGES_SQL, SCHEMA_PRIVILEGES_SQL,
                TABLE_PRIVILEGES_SQL, SEQUENCE_PRIVILEGES_SQL, ROUTINE_PRIVILEGES_SQL, PUBLIC_ACL_SQL);
    }

    static RoleMembershipSummary report(Connection connection, String principalKind, PrintStream output)
            throws SQLException {
        RoleMembershipSummary memberships = reportMemberships(connection, principalKind, output);
        reportDatabase(connection, principalKind, output);
        reportRows(connection, SCHEMA_PRIVILEGES_SQL, principalKind, "schema_effective_privilege", 2, output,
                "usage", "create");
        reportRows(connection, TABLE_PRIVILEGES_SQL, principalKind, "relation_effective_privilege", 3, output,
                "select", "insert", "update", "delete", "truncate", "references", "trigger",
                "column_select", "column_insert", "column_update", "column_references");
        reportRows(connection, SEQUENCE_PRIVILEGES_SQL, principalKind, "sequence_effective_privilege", 3, output,
                "usage", "select", "update");
        reportPublicAcls(connection, principalKind, output);
        reportRoutinePrivileges(connection, principalKind, output);
        return memberships;
    }

    private static RoleMembershipSummary reportMemberships(Connection connection, String principalKind,
            PrintStream output) throws SQLException {
        int members = 0;
        int inherited = 0;
        try (PreparedStatement statement = connection.prepareStatement(ROLE_MEMBERSHIPS_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String memberRole = safeIdentifier(rows.getString(1));
                boolean isMember = rows.getBoolean(2);
                boolean inherits = rows.getBoolean(3);
                if (isMember) members++;
                if (inherits) inherited++;
                output.println("database_role_membership=principal=" + principalKind
                        + ",member_of=" + memberRole + ",membership=" + isMember
                        + ",inherited_privileges=" + inherits);
            }
        }
        output.println("database_role_membership_summary=principal=" + principalKind
                + ",memberships=" + members + ",inherited_roles=" + inherited);
        return new RoleMembershipSummary(members, inherited);
    }

    private static void reportDatabase(Connection connection, String principalKind, PrintStream output)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DATABASE_PRIVILEGES_SQL);
             ResultSet row = statement.executeQuery()) {
            if (!row.next()) throw new SQLException("Read-only database privilege inspection returned no row.");
            output.println("database_effective_privilege=principal=" + principalKind
                    + ",database=" + safeIdentifier(row.getString(1))
                    + ",connect=" + row.getBoolean(2) + ",create=" + row.getBoolean(3)
                    + ",temporary=" + row.getBoolean(4));
        }
    }

    private static void reportRows(Connection connection, String sql, String principalKind, String recordName,
            int booleanStart, PrintStream output, String... privilegeNames) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String schema = safeIdentifier(rows.getString(1));
                String object = "schema_effective_privilege".equals(recordName) ? schema : safeIdentifier(rows.getString(2));
                output.print(recordName + "=principal=" + principalKind + ",schema=" + schema + ",name=" + object);
                for (int index = 0; index < privilegeNames.length; index++) {
                    output.print("," + privilegeNames[index] + "=" + rows.getBoolean(booleanStart + index));
                }
                output.println();
            }
        }
    }

    private static void reportPublicAcls(Connection connection, String principalKind, PrintStream output)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(PUBLIC_ACL_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                output.println("database_public_acl=principal=" + principalKind
                        + ",type=" + safeIdentifier(rows.getString(1))
                        + ",schema=" + safeIdentifier(rows.getString(2))
                        + ",name=" + safeIdentifier(rows.getString(3))
                        + ",privilege=" + safeIdentifier(rows.getString(4)));
            }
        }
    }

    private static void reportRoutinePrivileges(Connection connection, String principalKind, PrintStream output)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ROUTINE_PRIVILEGES_SQL);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                output.println("routine_effective_privilege=principal=" + principalKind
                        + ",schema=" + safeIdentifier(rows.getString(1))
                        + ",name=" + safeIdentifier(rows.getString(2)) + ",execute=true");
            }
        }
    }

    private static String safeIdentifier(String input) {
        if (input == null) return "unknown";
        String safe = input.replaceAll("[^A-Za-z0-9_.$() -]", "_");
        return safe.length() > 160 ? safe.substring(0, 160) : safe;
    }

    record RoleMembershipSummary(int memberships, int inheritedRoles) {
        boolean hasNoMemberships() { return memberships == 0; }
    }
}
