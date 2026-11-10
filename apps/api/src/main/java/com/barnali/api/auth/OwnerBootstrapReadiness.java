package com.barnali.api.auth;

import com.barnali.api.migration.MigrationTarget;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Read-only guard for the isolated execute-only owner provisioning principal. */
public final class OwnerBootstrapReadiness {
    private OwnerBootstrapReadiness() {}

    public static void verify(DataSource dataSource, Environment environment) {
        String url = required(environment.getProperty("barnali.owner.provisioner.database-url"), "owner-provisioner database URL");
        String username = required(environment.getProperty("barnali.owner.provisioner.database-username"), "owner-provisioner username");
        required(environment.getProperty("barnali.owner.provisioner.database-password"), "owner-provisioner password");
        String expectedProjectRef = required(environment.getProperty("barnali.expected-supabase-project-ref"),
                "operator-supplied Supabase project reference");
        if (!username.startsWith("barnali_provisioner.")) {
            throw new IllegalStateException("Owner provisioning must use the dedicated barnali_provisioner principal.");
        }
        com.barnali.api.migration.SupabaseProjectIdentity.requireUsernameMatches(username, expectedProjectRef);
        MigrationTarget.parse(url).requireBarnaliTarget();

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) { statement.execute("SET TRANSACTION READ ONLY"); }
                verifyConnectionAndPrincipal(connection);
                String readiness = scalar(connection, "SELECT barnali.owner_bootstrap_readiness()");
                if (!"READY".equals(readiness)) {
                    throw new IllegalStateException("Owner provisioning is already consumed or the owner state is inconsistent.");
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new OwnerBootstrapDiagnostics.SafeDatabaseException(exception.getSQLState());
        }
    }

    private static void verifyConnectionAndPrincipal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_database()='postgres',
                       COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()),FALSE),
                       current_schema()='barnali',
                       COALESCE((current_schemas(false))[1]='barnali',FALSE),
                       role_row.rolsuper,role_row.rolbypassrls,role_row.rolcreatedb,role_row.rolcreaterole,
                       has_database_privilege(role_row.oid,current_database(),'CREATE'),
                       has_schema_privilege(role_row.oid,'barnali','USAGE'),
                       has_schema_privilege(role_row.oid,'barnali','CREATE'),
                       schema_row.nspowner=role_row.oid,
                       current_user=session_user,
                       NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=role_row.oid),
                       NOT EXISTS (SELECT 1 FROM pg_namespace other_schema
                           WHERE other_schema.nspname<>'barnali' AND other_schema.nspname<>'information_schema'
                             AND other_schema.nspname !~ '^pg_' AND has_schema_privilege(role_row.oid,other_schema.oid,'CREATE')),
                       NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                           WHERE n.nspname='barnali' AND c.relkind IN ('r','p','v','m','f')
                             AND (has_table_privilege(role_row.oid,c.oid,'SELECT')
                               OR has_table_privilege(role_row.oid,c.oid,'INSERT')
                               OR has_table_privilege(role_row.oid,c.oid,'UPDATE')
                               OR has_table_privilege(role_row.oid,c.oid,'DELETE'))),
                       NOT EXISTS (SELECT 1 FROM pg_class s JOIN pg_namespace n ON n.oid=s.relnamespace
                           WHERE n.nspname='barnali' AND s.relkind='S'
                             AND (has_sequence_privilege(role_row.oid,s.oid,'USAGE')
                               OR has_sequence_privilege(role_row.oid,s.oid,'SELECT')
                               OR has_sequence_privilege(role_row.oid,s.oid,'UPDATE'))),
                       has_function_privilege(role_row.oid,'barnali.owner_bootstrap_readiness()','EXECUTE'),
                       has_function_privilege(role_row.oid,'barnali.provision_initial_owner(text,text)','EXECUTE')
                FROM pg_roles role_row JOIN pg_namespace schema_row ON schema_row.nspname='barnali'
                WHERE role_row.rolname=current_user
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("The owner-provisioner principal or private schema is unavailable.");
                boolean safe = row.getBoolean(1) && row.getBoolean(2) && row.getBoolean(3) && row.getBoolean(4)
                        && !row.getBoolean(5) && !row.getBoolean(6) && !row.getBoolean(7) && !row.getBoolean(8)
                        && !row.getBoolean(9) && row.getBoolean(10) && !row.getBoolean(11) && !row.getBoolean(12)
                        && row.getBoolean(13) && row.getBoolean(14) && row.getBoolean(15) && row.getBoolean(16)
                        && row.getBoolean(17) && row.getBoolean(18);
                if (!safe) throw new IllegalStateException("The owner-provisioner principal does not have the reviewed execute-only privileges.");
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet row = statement.executeQuery()) {
            if (!row.next()) throw new IllegalStateException("Owner bootstrap readiness could not be verified.");
            return row.getString(1);
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Required " + label + " is missing.");
        return value;
    }
}
