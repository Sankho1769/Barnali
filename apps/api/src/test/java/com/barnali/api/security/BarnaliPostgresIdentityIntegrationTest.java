package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barnali.api.auth.AppPrincipal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL contract tests for transaction-local identity and a non-owner RLS role.
 * Uses only synthetic tables and rows; it deliberately does not run Flyway or V1/V2.
 */
@Testcontainers(disabledWithoutDocker = true)
class BarnaliPostgresIdentityIntegrationTest {
    private static final String SCHEMA = "barnali_identity_it";
    private static final String RUNTIME_ROLE = "barnali_identity_runtime_it";
    private static final UUID USER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("barnali_it")
            .withUsername("barnali_test_admin")
            .withPassword(ADMIN_PASSWORD);

    private static JdbcTemplate adminJdbc;
    private static HikariDataSource runtimePool;
    private static JdbcTemplate runtimeJdbc;
    private static BarnaliJdbcTransactionManager transactionManager;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void createIsolatedFixture() {
        DriverManagerDataSource adminDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        adminJdbc = new JdbcTemplate(adminDataSource);

        adminJdbc.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS"
                + " PASSWORD '" + RUNTIME_PASSWORD + "'");
        adminJdbc.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION " + POSTGRES.getUsername());
        adminJdbc.execute("CREATE TABLE " + SCHEMA + ".records ("
                + "id uuid PRIMARY KEY, user_id uuid NOT NULL, payload text NOT NULL)");
        adminJdbc.execute("ALTER TABLE " + SCHEMA + ".records ENABLE ROW LEVEL SECURITY");
        adminJdbc.execute("CREATE POLICY records_by_transaction_identity ON " + SCHEMA + ".records"
                + " USING (user_id = NULLIF(current_setting('barnali.user_id', true), '')::uuid)"
                + " WITH CHECK (user_id = NULLIF(current_setting('barnali.user_id', true), '')::uuid)");
        adminJdbc.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + RUNTIME_ROLE);
        adminJdbc.execute("GRANT SELECT, INSERT, UPDATE ON " + SCHEMA + ".records TO " + RUNTIME_ROLE);

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        poolConfig.setUsername(RUNTIME_ROLE);
        poolConfig.setPassword(RUNTIME_PASSWORD);
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setMinimumIdle(1);
        poolConfig.setPoolName("barnali-isolated-identity-test");
        poolConfig.setConnectionInitSql("SELECT set_config('barnali.user_id', '', false)");
        runtimePool = new HikariDataSource(poolConfig);
        runtimeJdbc = new JdbcTemplate(runtimePool);
        transactionManager = new BarnaliJdbcTransactionManager(runtimePool);
        transactions = new TransactionTemplate(transactionManager);
    }

    @AfterAll
    static void closePool() {
        if (runtimePool != null) runtimePool.close();
    }

    @BeforeEach
    void seedSyntheticRows() {
        adminJdbc.update("DELETE FROM " + SCHEMA + ".records");
        adminJdbc.update("INSERT INTO " + SCHEMA + ".records (id, user_id, payload) VALUES (?, ?, ?)",
                UUID.randomUUID(), USER_A, "synthetic-a");
        adminJdbc.update("INSERT INTO " + SCHEMA + ".records (id, user_id, payload) VALUES (?, ?, ?)",
                UUID.randomUUID(), USER_B, "synthetic-b");
    }

    @AfterEach
    void clearRequestIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatedPrincipalIsBoundToTheSameTransactionConnectionAndRlsLimitsRows() {
        authenticate(USER_A);

        Integer visibleRows = transactions.execute(status -> {
            assertThat(currentIdentity()).isEqualTo(USER_A.toString());
            assertRuntimeRoleIsNonOwnerWithoutBypassRls();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".records", Integer.class))
                    .isEqualTo(1);
            return runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                    + ".records WHERE user_id = ?", Integer.class, USER_B);
        });

        assertThat(visibleRows).isZero();
    }

    @Test
    void anonymousTransactionGetsEmptyIdentityAndCannotSeeProtectedRows() {
        SecurityContextHolder.clearContext();

        Integer visibleRows = transactions.execute(status -> {
            assertThat(currentIdentity()).isEmpty();
            return runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".records", Integer.class);
        });

        assertThat(visibleRows).isZero();
    }

    @Test
    void transactionLocalIdentityResetsAfterCommitAndRollback() {
        UUID committedId = UUID.randomUUID();
        authenticate(USER_A);
        transactions.execute(status -> {
            assertThat(currentIdentity()).isEqualTo(USER_A.toString());
            runtimeJdbc.update("INSERT INTO " + SCHEMA + ".records (id, user_id, payload) VALUES (?, ?, ?)",
                    committedId, USER_A, "committed-synthetic-row");
            return null;
        });
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".records WHERE id = ?", Integer.class, committedId)).isEqualTo(1);
        assertPooledIdentityIsEmpty();

        UUID rolledBackId = UUID.randomUUID();
        transactions.execute(status -> {
            assertThat(currentIdentity()).isEqualTo(USER_A.toString());
            runtimeJdbc.update("INSERT INTO " + SCHEMA + ".records (id, user_id, payload) VALUES (?, ?, ?)",
                    rolledBackId, USER_A, "rolled-back-synthetic-row");
            status.setRollbackOnly();
            return null;
        });
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".records WHERE id = ?", Integer.class, rolledBackId)).isZero();
        assertPooledIdentityIsEmpty();
    }

    @Test
    void onePooledConnectionCanSwitchUsersWithoutLeakingIdentityOrRows() {
        authenticate(USER_A);
        Integer backendForA = transactions.execute(status -> {
            assertThat(currentIdentity()).isEqualTo(USER_A.toString());
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".records", Integer.class))
                    .isEqualTo(1);
            return runtimeJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        });

        authenticate(USER_B);
        Integer backendForB = transactions.execute(status -> {
            assertThat(currentIdentity()).isEqualTo(USER_B.toString());
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".records", Integer.class))
                    .isEqualTo(1);
            return runtimeJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        });

        SecurityContextHolder.clearContext();
        Integer anonymousRows = transactions.execute(status -> {
            assertThat(currentIdentity()).isEmpty();
            return runtimeJdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".records", Integer.class);
        });

        assertThat(backendForB).isEqualTo(backendForA);
        assertThat(anonymousRows).isZero();
        assertPooledIdentityIsEmpty();
    }

    @Test
    void runtimeRoleCannotChangeRowsOwnedByAnotherIdentityOrInsertForThem() {
        authenticate(USER_A);
        Integer changedRows = transactions.execute(status -> runtimeJdbc.update("UPDATE " + SCHEMA
                + ".records SET payload = 'tampered' WHERE user_id = ?", USER_B));
        assertThat(changedRows).isZero();

        assertThatThrownBy(() -> transactions.execute(status -> runtimeJdbc.update("INSERT INTO " + SCHEMA
                + ".records (id, user_id, payload) VALUES (?, ?, ?)", UUID.randomUUID(), USER_B, "forbidden")))
                .isInstanceOf(DataAccessException.class);
    }

    private static void assertRuntimeRoleIsNonOwnerWithoutBypassRls() {
        RuntimeRoleState state = runtimeJdbc.queryForObject("SELECT current_user, r.rolsuper, r.rolbypassrls, "
                        + "pg_get_userbyid(c.relowner), has_schema_privilege(current_user, ?, 'CREATE') "
                        + "FROM pg_roles r CROSS JOIN pg_class c "
                        + "WHERE r.rolname = current_user AND c.oid = ?::regclass",
                (result, row) -> new RuntimeRoleState(result.getString(1), result.getBoolean(2),
                        result.getBoolean(3), result.getString(4), result.getBoolean(5)),
                SCHEMA, SCHEMA + ".records");
        assertThat(state.role()).isEqualTo(RUNTIME_ROLE);
        assertThat(state.superuser()).isFalse();
        assertThat(state.bypassRls()).isFalse();
        assertThat(state.role()).isNotEqualTo(state.tableOwner());
        assertThat(state.schemaCreate()).isFalse();
    }

    private static void assertPooledIdentityIsEmpty() {
        assertThat(runtimeJdbc.queryForObject("SELECT current_setting('barnali.user_id', true)", String.class))
                .isEmpty();
    }

    private static String currentIdentity() {
        return runtimeJdbc.queryForObject("SELECT current_setting('barnali.user_id', true)", String.class);
    }

    private static void authenticate(UUID userId) {
        AppPrincipal principal = new AppPrincipal(userId, "synthetic@example.invalid", "synthetic", "Synthetic",
                "not-a-password-hash", "ACTIVE", false, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private record RuntimeRoleState(String role, boolean superuser, boolean bypassRls,
            String tableOwner, boolean schemaCreate) {}
}
