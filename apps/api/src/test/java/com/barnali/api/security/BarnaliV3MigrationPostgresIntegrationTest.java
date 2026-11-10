package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.barnali.api.auth.AppPrincipal;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Disposable PostgreSQL integration fixture for the actual V1, V2 and V3 migrations.
 * It creates synthetic roles/data only. It never loads application environment files or
 * uses external database credentials. Run only with -Ppostgres-integration.
 */
@Testcontainers(disabledWithoutDocker = true)
class BarnaliV3MigrationPostgresIntegrationTest {
    private static final String SCHEMA = "barnali";
    private static final String MIGRATOR = "barnali_migrator";
    private static final String RUNTIME = "barnali_runtime";
    private static final String PROVISIONER = "barnali_provisioner";
    private static final UUID MEMBER_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEMBER_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MODERATOR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SUPER_ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ANALYTICS_VIEWER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID SUPPORT_ADMIN = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID PUBLIC_ROOM = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PRIVATE_ROOM = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID PUBLIC_DISCUSSION = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID PRIVATE_DISCUSSION = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID HIDDEN_DISCUSSION = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final String ADMIN_PASSWORD = syntheticPassword();
    private static final String MIGRATOR_PASSWORD = syntheticPassword();
    private static final String RUNTIME_PASSWORD = syntheticPassword();
    private static final String PROVISIONER_PASSWORD = syntheticPassword();
    private static final BCryptPasswordEncoder HASHER = new BCryptPasswordEncoder(4);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("barnali_v3_it")
            .withUsername("barnali_fixture_admin")
            .withPassword(ADMIN_PASSWORD);

    private static JdbcTemplate adminJdbc;
    private static JdbcTemplate migratorJdbc;
    private static JdbcTemplate provisionerJdbc;
    private static HikariDataSource runtimePool;
    private static HikariDataSource identityPool;
    private static JdbcTemplate runtimeJdbc;
    private static JdbcTemplate identityJdbc;
    private static TransactionTemplate transactions;
    private static TransactionTemplate identityTransactions;
    private static Flyway flyway;
    private static UUID publicReportId;

    @BeforeAll
    static void createDisposableDatabaseAndApplyActualMigrations() {
        adminJdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        // These three synthetic roles mirror the documented role separation. Only the
        // container's disposable administrator provisions roles and the private schema.
        createLoginRole(MIGRATOR, MIGRATOR_PASSWORD);
        createLoginRole(RUNTIME, RUNTIME_PASSWORD);
        createLoginRole(PROVISIONER, PROVISIONER_PASSWORD);
        adminJdbc.execute("REVOKE CONNECT ON DATABASE barnali_v3_it FROM PUBLIC");
        adminJdbc.execute("GRANT CONNECT ON DATABASE barnali_v3_it TO " + MIGRATOR + "," + RUNTIME + "," + PROVISIONER);
        adminJdbc.execute("CREATE SCHEMA barnali AUTHORIZATION " + MIGRATOR);

        // No clean(), baseline(), Spring context, API startup or application bootstrap.
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(false)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().migrationsExecuted()).isEqualTo(3);
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();

        migratorJdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "?currentSchema=barnali", MIGRATOR, MIGRATOR_PASSWORD));
        provisionerJdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "?currentSchema=barnali", PROVISIONER, PROVISIONER_PASSWORD));
        runtimePool = createRuntimePool();
        runtimeJdbc = new JdbcTemplate(runtimePool);
        transactions = new TransactionTemplate(new BarnaliJdbcTransactionManager(runtimePool));
        identityPool = createRuntimePool("barnali-v3-identity-single-connection", 1);
        identityJdbc = new JdbcTemplate(identityPool);
        identityTransactions = new TransactionTemplate(new BarnaliJdbcTransactionManager(identityPool));
    }

    @AfterAll
    static void closeDisposablePools() {
        if (runtimePool != null) runtimePool.close();
        if (identityPool != null) identityPool.close();
    }

    @BeforeEach
    void resetOnlySyntheticFixtureRows() {
        // users is the parent of all per-test fixture rows; CASCADE is confined to the
        // disposable container. Flyway history and migration objects are not truncated.
        adminJdbc.execute("TRUNCATE TABLE barnali.users CASCADE");
        migratorJdbc.update("UPDATE barnali.admin_bootstrap_guard SET consumed_at=NULL WHERE id=1");
        seedUser(MEMBER_A, "member_a", "member-a@example.invalid", null);
        seedUser(MEMBER_B, "member_b", "member-b@example.invalid", null);
        seedUser(MODERATOR, "moderator", "moderator@example.invalid", "MODERATOR");
        seedUser(SUPER_ADMIN, "super_admin", "super-admin@example.invalid", "SUPER_ADMIN");
        seedUser(ANALYTICS_VIEWER, "analytics_viewer", "analytics@example.invalid", "ANALYTICS_VIEWER");
        seedUser(SUPPORT_ADMIN, "support_admin", "support@example.invalid", "SUPPORT_ADMIN");

        adminJdbc.update("INSERT INTO barnali.rooms(id,slug,name,description,is_public,is_active) VALUES"
                + " (?, 'public-room', 'Public room', 'synthetic public room', TRUE, TRUE),"
                + " (?, 'private-room', 'Private room', 'synthetic private room', FALSE, TRUE)",
                PUBLIC_ROOM, PRIVATE_ROOM);
        adminJdbc.update("INSERT INTO barnali.room_memberships(room_id,user_id) VALUES (?,?),(?,?)",
                PUBLIC_ROOM, MEMBER_A, PRIVATE_ROOM, MEMBER_B);
        adminJdbc.update("INSERT INTO barnali.discussions(id,slug,room_id,author_id,title,body,status) VALUES"
                + " (?, 'public-topic', ?, ?, 'Synthetic public topic', 'Synthetic public body', 'PUBLISHED'),"
                + " (?, 'private-topic', ?, ?, 'Synthetic private topic', 'Synthetic private body', 'PUBLISHED'),"
                + " (?, 'hidden-topic', ?, ?, 'Synthetic hidden topic', 'Synthetic hidden body', 'HIDDEN')",
                PUBLIC_DISCUSSION, PUBLIC_ROOM, MEMBER_A, PRIVATE_DISCUSSION, PRIVATE_ROOM, MEMBER_B,
                HIDDEN_DISCUSSION, PUBLIC_ROOM, MEMBER_A);
        adminJdbc.update("INSERT INTO barnali.discussion_votes(discussion_id,user_id,value) VALUES (?,?,1)",
                PUBLIC_DISCUSSION, MEMBER_B);
        adminJdbc.update("INSERT INTO barnali.user_activity_events(user_id,event_type) VALUES (?, 'LOGIN')", MEMBER_B);
        adminJdbc.update("INSERT INTO barnali.reports(reporter_id,target_type,target_id,reason,details)"
                + " VALUES (?, 'DISCUSSION', ?, 'SPAM', 'synthetic report')", MEMBER_B, PUBLIC_DISCUSSION);
        publicReportId = adminJdbc.queryForObject("SELECT id FROM barnali.reports", UUID.class);
    }

    @AfterEach
    void clearSpringIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allMigrationsApplyInOrderAndCreateExpectedSecurityObjects() {
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3");
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema='barnali' AND table_type='BASE TABLE'", Integer.class)).isEqualTo(15);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_policies"
                + " WHERE schemaname='barnali'", Integer.class)).isGreaterThanOrEqualTo(25);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace"
                + " WHERE n.nspname='barnali' AND c.relkind='v'", Integer.class)).isEqualTo(5);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace"
                + " WHERE n.nspname='barnali' AND c.relkind='r' AND c.relrowsecurity", Integer.class)).isEqualTo(14);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_indexes"
                + " WHERE schemaname='barnali' AND indexname='reports_one_pending_report_per_reporter_target_idx'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void runtimeRoleHasOnlyReviewedAclAndCannotReadSensitiveColumnsOrOwnProtectedTables() {
        Map<String, Object> role = adminJdbc.queryForMap("SELECT r.rolsuper,r.rolbypassrls,r.rolcreatedb,r.rolcreaterole,"
                + " pg_catalog.has_database_privilege(r.rolname,current_database(),'CREATE') AS database_create,"
                + " pg_catalog.has_schema_privilege(r.rolname,'barnali','CREATE') AS schema_create,"
                + " pg_catalog.pg_get_userbyid(c.relowner) AS table_owner"
                + " FROM pg_catalog.pg_roles r CROSS JOIN pg_catalog.pg_class c"
                + " WHERE r.rolname=? AND c.oid='barnali.users'::regclass", RUNTIME);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
        assertThat(role.get("rolcreatedb")).isEqualTo(false);
        assertThat(role.get("rolcreaterole")).isEqualTo(false);
        assertThat(role.get("database_create")).isEqualTo(false);
        assertThat(role.get("schema_create")).isEqualTo(false);
        assertThat(role.get("table_owner")).isEqualTo(MIGRATOR);
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace"
                + " JOIN pg_catalog.pg_roles r ON r.oid=c.relowner"
                + " WHERE n.nspname='barnali' AND c.relkind IN ('r','v') AND r.rolname=?",
                Integer.class, RUNTIME)).isZero();
        assertThat(adminJdbc.queryForObject("SELECT pg_catalog.has_database_privilege(?,current_database(),'CREATE')"
                + " OR pg_catalog.has_schema_privilege(?,'public','CREATE')"
                + " OR NOT pg_catalog.has_schema_privilege(?,'barnali','CREATE')",
                Boolean.class, MIGRATOR, MIGRATOR, MIGRATOR)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT count(*) FROM pg_catalog.pg_auth_members"
                + " WHERE member IN (SELECT oid FROM pg_catalog.pg_roles"
                + " WHERE rolname IN (?,?,?))", Integer.class, MIGRATOR, RUNTIME, PROVISIONER)).isZero();
        assertThat(adminJdbc.queryForObject("SELECT has_column_privilege(?, 'barnali.users', 'id', 'SELECT')",
                Boolean.class, RUNTIME)).isTrue();
        assertThat(adminJdbc.queryForObject("SELECT has_column_privilege(?, 'barnali.users', 'password_hash', 'SELECT')",
                Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali.password_reset_tokens', 'SELECT')",
                Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali.admin_bootstrap_guard', 'SELECT')",
                Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali.admin_audit_events', 'UPDATE')",
                Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_sequence_privilege(?, 'barnali.user_activity_events_id_seq', 'USAGE')",
                Boolean.class, RUNTIME)).isTrue();
        assertThat(adminJdbc.queryForObject("SELECT has_sequence_privilege(?, 'barnali.user_activity_events_id_seq', 'USAGE')",
                Boolean.class, PROVISIONER)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_schema_privilege(?, 'barnali', 'USAGE')",
                Boolean.class, PROVISIONER)).isTrue();
        assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali.users', 'SELECT')",
                Boolean.class, PROVISIONER)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_function_privilege(?,"
                + " 'barnali.provision_initial_owner(text,text)', 'EXECUTE')", Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_function_privilege(?,"
                + " 'barnali.provision_initial_owner(text,text)', 'EXECUTE')", Boolean.class, PROVISIONER)).isTrue();
        assertThat(adminJdbc.queryForObject("SELECT has_function_privilege(?,"
                + " 'barnali.auth_principal_by_email(text)', 'EXECUTE')", Boolean.class, RUNTIME)).isTrue();
        assertThat(adminJdbc.queryForObject("SELECT has_function_privilege(?,"
                + " 'barnali.owner_bootstrap_readiness()', 'EXECUTE')", Boolean.class, RUNTIME)).isFalse();
        assertThat(adminJdbc.queryForObject("SELECT has_function_privilege(?,"
                + " 'barnali.owner_bootstrap_readiness()', 'EXECUTE')", Boolean.class, PROVISIONER)).isTrue();
        for (String view : List.of("public_room_summaries", "admin_user_rows", "admin_team_rows",
                "admin_room_rows", "admin_audit_rows")) {
            assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali." + view + "', 'SELECT')",
                    Boolean.class, RUNTIME)).isTrue();
            assertThat(adminJdbc.queryForObject("SELECT has_table_privilege(?, 'barnali." + view + "', 'SELECT')",
                    Boolean.class, PROVISIONER)).isFalse();
        }
    }

    @Test
    void publicContentIsVisibleButMembersCannotReadOtherUsersPrivateRows() {
        SecurityContextHolder.clearContext();
        Map<String, Object> anonymous = transactions.execute(status -> {
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.discussions WHERE id=?",
                    Integer.class, PUBLIC_DISCUSSION)).isEqualTo(1);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.discussions WHERE id=?",
                    Integer.class, PRIVATE_DISCUSSION)).isZero();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.discussions WHERE id=?",
                    Integer.class, HIDDEN_DISCUSSION)).isZero();
            return runtimeJdbc.queryForMap("SELECT count(*) AS rooms, sum(member_count)::bigint AS members,"
                    + " sum(discussion_count)::bigint AS discussions FROM barnali.public_room_summaries");
        });
        assertThat(anonymous.get("rooms")).isEqualTo(1L);
        assertThat(anonymous.get("discussions")).isEqualTo(1L);

        authenticate(MEMBER_A, "member_a@example.invalid", null);
        transactions.executeWithoutResult(status -> {
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.users WHERE id=?",
                    Integer.class, MEMBER_B)).isZero();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.room_memberships WHERE user_id=?",
                    Integer.class, MEMBER_B)).isZero();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.discussion_votes WHERE user_id=?",
                    Integer.class, MEMBER_B)).isZero();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.reports WHERE reporter_id=?",
                    Integer.class, MEMBER_B)).isZero();
            assertThat(runtimeJdbc.update("UPDATE barnali.users SET status='SUSPENDED' WHERE id=?", MEMBER_B)).isZero();
        });
        assertThatThrownBy(() -> transactions.execute(status -> runtimeJdbc.queryForObject(
                "SELECT count(*) FROM barnali.user_activity_events WHERE user_id=?", Integer.class, MEMBER_B)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transactions.execute(status -> runtimeJdbc.update(
                "INSERT INTO barnali.discussion_votes(discussion_id,user_id,value) VALUES (?, ?, 1)",
                PUBLIC_DISCUSSION, MEMBER_B))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void reportRoutineRequiresAuthenticatedIdentityAndHidesInvalidTargets() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> runtimeJdbc.queryForMap("SELECT * FROM barnali.submit_report(?,?,?,?)",
                "DISCUSSION", PUBLIC_DISCUSSION, "SPAM", "synthetic anonymous report"))
                .isInstanceOf(DataAccessException.class);

        authenticate(MEMBER_A, "member-a@example.invalid", null);
        Map<String, Object> created = transactions.execute(status -> runtimeJdbc.queryForMap(
                "SELECT * FROM barnali.submit_report(?,?,?,?)", "DISCUSSION", PUBLIC_DISCUSSION,
                "MISINFORMATION", "synthetic report from member A"));
        assertThat(created.get("result")).isEqualTo("CREATED");
        assertThat(created.get("created_id")).isNotNull();
        Map<String, Object> duplicate = transactions.execute(status -> runtimeJdbc.queryForMap(
                "SELECT * FROM barnali.submit_report(?,?,?,?)", "DISCUSSION", PUBLIC_DISCUSSION,
                "MISINFORMATION", "duplicate synthetic report"));
        assertThat(duplicate.get("result")).isEqualTo("DUPLICATE");
        Map<String, Object> privateTarget = transactions.execute(status -> runtimeJdbc.queryForMap(
                "SELECT * FROM barnali.submit_report(?,?,?,?)", "DISCUSSION", PRIVATE_DISCUSSION,
                "SPAM", "synthetic private target"));
        assertThat(privateTarget.get("result")).isEqualTo("NOT_FOUND");
    }

    @Test
    void identityUsesTransactionLocalSettingAndDoesNotLeakWhenPoolConnectionIsReused() {
        authenticate(MEMBER_A, "member_a@example.invalid", null);
        Integer backendA = identityTransactions.execute(status -> {
            assertThat(identityJdbc.queryForObject("SELECT current_setting('barnali.user_id',true)", String.class))
                    .isEqualTo(MEMBER_A.toString());
            assertThat(identityJdbc.queryForObject("SELECT count(*) FROM barnali.room_memberships",
                    Integer.class)).isEqualTo(1);
            return identityJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        });
        assertEmptySessionIdentity(identityJdbc);

        authenticate(MEMBER_B, "member_b@example.invalid", null);
        Integer backendB = identityTransactions.execute(status -> {
            assertThat(identityJdbc.queryForObject("SELECT current_setting('barnali.user_id',true)", String.class))
                    .isEqualTo(MEMBER_B.toString());
            assertThat(identityJdbc.queryForObject("SELECT count(*) FROM barnali.room_memberships",
                    Integer.class)).isEqualTo(1);
            return identityJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        });
        assertThat(backendB).isEqualTo(backendA);
        assertEmptySessionIdentity(identityJdbc);

        SecurityContextHolder.clearContext();
        identityTransactions.executeWithoutResult(status -> {
            assertThat(identityJdbc.queryForObject("SELECT current_setting('barnali.user_id',true)", String.class))
                    .isEmpty();
            assertThat(identityJdbc.queryForObject("SELECT count(*) FROM barnali.room_memberships",
                    Integer.class)).isZero();
        });

        authenticate(MEMBER_A, "member_a@example.invalid", null);
        identityTransactions.execute(status -> {
            status.setRollbackOnly();
            assertThat(identityJdbc.queryForObject("SELECT current_setting('barnali.user_id',true)", String.class))
                    .isEqualTo(MEMBER_A.toString());
            return null;
        });
        assertEmptySessionIdentity(identityJdbc);
        authenticate(MEMBER_B, "member_b@example.invalid", null);
        identityTransactions.executeWithoutResult(status -> assertThat(identityJdbc.queryForObject(
                "SELECT current_setting('barnali.user_id',true)", String.class)).isEqualTo(MEMBER_B.toString()));
        assertEmptySessionIdentity(identityJdbc);
    }

    @Test
    void authenticationRegistrationRateLimitAndRecoveryFunctionsHideDirectTables() {
        String expectedHash = migratorJdbc.queryForObject("SELECT password_hash FROM barnali.users WHERE id=?",
                String.class, MEMBER_A);
        String lookedUpHash = runtimeJdbc.queryForObject("SELECT password_hash FROM barnali.auth_principal_by_email(?)",
                String.class, "member-a@example.invalid");
        assertThat(lookedUpHash).isEqualTo(expectedHash);
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT password_hash FROM barnali.users WHERE id=?",
                String.class, MEMBER_A)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.update("INSERT INTO barnali.users(username,display_name,email,password_hash)"
                + " VALUES ('direct_user','Direct','direct@example.invalid',?)", HASHER.encode("synthetic-password")))
                .isInstanceOf(DataAccessException.class);

        assertThat(runtimeJdbc.queryForObject("SELECT barnali.register_user(?,?,?,?)", Boolean.class,
                "Synthetic Registration", "synthetic_new", "synthetic-new@example.invalid",
                HASHER.encode("synthetic-password"))).isTrue();
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT barnali.register_user(?,?,?,?)", Boolean.class,
                "Bad Hash", "bad_hash_user", "bad-hash@example.invalid", "plaintext"))
                .isInstanceOf(DataAccessException.class);

        String keyA = "a".repeat(64);
        String keyB = "b".repeat(64);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.login_limit_record_attempt(?,?)", Boolean.class,
                keyA, keyB)).isTrue();
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.login_limit_blocked(?,?)", Boolean.class,
                keyA, keyB)).isFalse();
        for (int i = 0; i < 6; i++) runtimeJdbc.queryForObject(
                "SELECT barnali.login_limit_record_attempt(?,?)", Boolean.class, keyA, keyB);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.login_limit_blocked(?,?)", Boolean.class,
                keyA, keyB)).isTrue();
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.auth_login_limits", Integer.class))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.password_reset_tokens", Integer.class))
                .isInstanceOf(DataAccessException.class);

        String token = "c".repeat(64);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.issue_password_reset(?,?)", Boolean.class,
                "member-a@example.invalid", token)).isTrue();
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.issue_password_reset(?,?)", Boolean.class,
                "missing@example.invalid", "d".repeat(64))).isFalse();
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.complete_password_reset(?,?,?)", Boolean.class,
                token, HASHER.encode("replacement-synthetic-password"), "test-reset")).isTrue();
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.complete_password_reset(?,?,?)", Boolean.class,
                token, HASHER.encode("another-synthetic-password"), "test-reset-again")).isFalse();
        assertThat(migratorJdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM barnali.password_reset_tokens"
                + " WHERE token_hash=?", Boolean.class, token)).isTrue();
    }

    @Test
    void concurrentRecoveryCompletionsConsumeOneTokenExactlyOnce() throws Exception {
        String token = "e".repeat(64);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.issue_password_reset(?,?)", Boolean.class,
                "member-a@example.invalid", token)).isTrue();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> completeAfter(start, token, "parallel-one"));
            Future<Boolean> second = pool.submit(() -> completeAfter(start, token, "parallel-two"));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(migratorJdbc.queryForObject("SELECT count(*) FROM barnali.password_reset_tokens"
                + " WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, token)).isEqualTo(1);
    }

    @Test
    void moderationAnalyticsAndAdminViewsRespectDatabaseRoles() {
        authenticate(MODERATOR, "moderator@example.invalid", null);
        transactions.executeWithoutResult(status -> {
            assertThat(runtimeJdbc.queryForObject("SELECT barnali.actor_has_permission('REPORT_RESOLVE')",
                    Boolean.class)).isTrue();
            assertThat(runtimeJdbc.queryForObject("SELECT barnali.actor_has_permission('ROOM_MANAGE')",
                    Boolean.class)).isFalse();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.reports WHERE id=?",
                    Integer.class, publicReportId)).isEqualTo(1);
            assertThat(runtimeJdbc.update("UPDATE barnali.reports SET status='RESOLVED',reviewer_id=?,"
                    + "resolution='synthetic moderation',resolved_at=now() WHERE id=?", MODERATOR, publicReportId))
                    .isEqualTo(1);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.admin_team_rows", Integer.class)).isZero();
        });
        assertThatThrownBy(() -> transactions.execute(status -> runtimeJdbc.update(
                "INSERT INTO barnali.rooms(slug,name,description,created_by)"
                        + " VALUES ('forbidden-room','Forbidden','Synthetic',?)", MODERATOR)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> transactions.execute(status -> runtimeJdbc.queryForObject(
                "SELECT * FROM barnali.admin_overview(?,?)", (rs, row) -> rs.getLong(1), fromDate(), toDate())))
                .isInstanceOf(DataAccessException.class);

        authenticate(ANALYTICS_VIEWER, "analytics@example.invalid", null);
        transactions.executeWithoutResult(status -> {
            assertThat(runtimeJdbc.queryForObject("SELECT barnali.actor_has_permission('ANALYTICS_READ')",
                    Boolean.class)).isTrue();
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.admin_room_rows", Integer.class))
                    .isEqualTo(2);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.admin_team_rows", Integer.class))
                    .isZero();
            assertThat(runtimeJdbc.queryForObject("SELECT total_users FROM barnali.admin_analytics_overview(?,?)",
                    Long.class, fromDate(), toDate()))
                    .isGreaterThanOrEqualTo(6L);
        });

        authenticate(SUPER_ADMIN, "super-admin@example.invalid", null);
        transactions.executeWithoutResult(status -> {
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.admin_user_rows", Integer.class))
                    .isEqualTo(6);
            assertThat(runtimeJdbc.queryForObject("SELECT count(*) FROM barnali.admin_audit_rows", Integer.class))
                    .isZero();
        });
        for (String view : List.of("public_room_summaries", "admin_user_rows", "admin_team_rows",
                "admin_room_rows", "admin_audit_rows")) {
            assertThat(viewColumns(view)).doesNotContain("password_hash", "token_hash", "token");
            assertThat(viewOwner(view)).isEqualTo(MIGRATOR);
            assertThat(adminJdbc.queryForObject("SELECT reloptions @> ARRAY['security_barrier=true']"
                    + " FROM pg_catalog.pg_class WHERE oid=('barnali." + view + "')::regclass", Boolean.class)).isTrue();
        }
    }

    @Test
    void invitationsRequireOwnerAndAcceptanceDoesNotReturnStoredTokenOrPasswordHash() {
        UUID ownerId = UUID.randomUUID();
        seedUser(ownerId, "synthetic_owner", "synthetic-owner@example.invalid", "OWNER");
        UUID invitationId = UUID.randomUUID();
        String invitationHash = "f".repeat(64);
        authenticate(SUPPORT_ADMIN, "support@example.invalid", null);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.create_admin_invitation(?,?,?,?,?)", String.class,
                invitationId, "invitee@example.invalid", "MODERATOR", invitationHash,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)))).isEqualTo("DENIED");

        authenticate(ownerId, "synthetic-owner@example.invalid", null);
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.create_admin_invitation(?,?,?,?,?)", String.class,
                invitationId, "invitee@example.invalid", "MODERATOR", invitationHash,
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)))).isEqualTo("CREATED");
        assertThat(runtimeJdbc.queryForList("SELECT * FROM barnali.invitation_by_token(?)", invitationHash))
                .containsExactly(Map.of("email", "invitee@example.invalid", "role", "MODERATOR"));
        assertThat(runtimeJdbc.queryForObject("SELECT barnali.accept_invitation(?,?,?,?,?)", Boolean.class,
                invitationHash, null, "Synthetic Invitee", "synthetic_invitee",
                HASHER.encode("synthetic-invite-password"))).isTrue();
        assertThat(migratorJdbc.queryForObject("SELECT role FROM barnali.admin_accounts a JOIN barnali.users u"
                + " ON u.id=a.user_id WHERE u.email='invitee@example.invalid'", String.class)).isEqualTo("MODERATOR");
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT barnali.accept_invitation(?,?,?,?,?)", Boolean.class,
                invitationHash, null, "Synthetic Invitee", "synthetic_invitee2",
                HASHER.encode("synthetic-invite-password"))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void ownerProvisioningIsSeparateAtomicAndGuardStaysConsumedAfterOwnerDeletion() throws Exception {
        assertThat(provisionerJdbc.queryForObject("SELECT barnali.owner_bootstrap_readiness()", String.class))
                .isEqualTo("READY");
        assertThatThrownBy(() -> runtimeJdbc.queryForObject("SELECT barnali.provision_initial_owner(?,?)",
                Boolean.class, "owner@example.invalid", HASHER.encode("synthetic-owner-password")))
                .isInstanceOf(DataAccessException.class);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successful = new AtomicInteger();
        try {
            Future<?> one = pool.submit(() -> provisionAfter(start, "first-owner@example.invalid", successful));
            Future<?> two = pool.submit(() -> provisionAfter(start, "second-owner@example.invalid", successful));
            start.countDown();
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(successful.get()).isEqualTo(1);
        assertThat(provisionerJdbc.queryForObject("SELECT barnali.owner_bootstrap_readiness()", String.class))
                .isEqualTo("CONSUMED");
        migratorJdbc.update("DELETE FROM barnali.admin_accounts WHERE role='OWNER'");
        migratorJdbc.update("DELETE FROM barnali.users WHERE email IN (?,?)",
                "first-owner@example.invalid", "second-owner@example.invalid");
        assertThat(migratorJdbc.queryForObject("SELECT count(*) FROM barnali.admin_accounts WHERE role='OWNER'",
                Integer.class)).isZero();
        assertThat(provisionerJdbc.queryForObject("SELECT barnali.owner_bootstrap_readiness()", String.class))
                .isEqualTo("CONSUMED");
        assertThatThrownBy(() -> provisionerJdbc.queryForObject("SELECT barnali.provision_initial_owner(?,?)",
                Boolean.class, "replacement-owner@example.invalid", HASHER.encode("replacement-synthetic-password")))
                .isInstanceOf(DataAccessException.class);
        assertThat(migratorJdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM barnali.admin_bootstrap_guard"
                + " WHERE id=1", Boolean.class)).isTrue();
    }

    private static void createLoginRole(String role, String password) {
        adminJdbc.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE"
                + " NOINHERIT NOBYPASSRLS PASSWORD '" + password + "'");
    }

    private static HikariDataSource createRuntimePool() {
        return createRuntimePool("barnali-v3-synthetic-runtime", 4);
    }

    private static HikariDataSource createRuntimePool(String poolName, int maxSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(RUNTIME);
        config.setPassword(RUNTIME_PASSWORD);
        config.setSchema(SCHEMA);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setPoolName(poolName);
        config.setConnectionInitSql("SELECT set_config('barnali.user_id', '', false)");
        return new HikariDataSource(config);
    }

    private static void seedUser(UUID id, String username, String email, String adminRole) {
        String hash = HASHER.encode("synthetic-password-for-test-only");
        adminJdbc.update("INSERT INTO barnali.users(id,username,display_name,email,password_hash)"
                + " VALUES (?,?,?,?,?)", id, username, username, email, hash);
        if (adminRole != null) adminJdbc.update("INSERT INTO barnali.admin_accounts(user_id,role,active)"
                + " VALUES (?,?,TRUE)", id, adminRole);
    }

    private static void authenticate(UUID id, String email, String adminRole) {
        AppPrincipal principal = new AppPrincipal(id, email, "synthetic", "Synthetic", "synthetic-hash-only",
                "ACTIVE", false, 0);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static void assertEmptySessionIdentity(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT current_setting('barnali.user_id',true)", String.class)).isEmpty();
    }

    private static java.sql.Date fromDate() {
        return java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(7));
    }

    private static java.sql.Date toDate() {
        return java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC));
    }

    private static String viewOwner(String view) {
        return adminJdbc.queryForObject("SELECT pg_catalog.pg_get_userbyid(c.relowner) FROM pg_catalog.pg_class c"
                + " WHERE c.oid=('barnali." + view + "')::regclass", String.class);
    }

    private static List<String> viewColumns(String view) {
        return adminJdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema='barnali' AND table_name=? ORDER BY ordinal_position", String.class, view);
    }

    private static Boolean completeAfter(CountDownLatch start, String token, String requestId) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        return runtimeJdbc.queryForObject("SELECT barnali.complete_password_reset(?,?,?)", Boolean.class,
                token, HASHER.encode("synthetic-concurrent-reset"), requestId);
    }

    private static void provisionAfter(CountDownLatch start, String email, AtomicInteger successful) {
        try {
            start.await(5, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(provisionerJdbc.queryForObject("SELECT barnali.provision_initial_owner(?,?)",
                    Boolean.class, email, HASHER.encode("synthetic-owner-provision-password")))) successful.incrementAndGet();
        } catch (DataAccessException expectedForLosingConcurrentCall) {
            // One-shot guard contention is expected; exception text is deliberately ignored.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Synthetic owner fixture interrupted", interrupted);
        }
    }

    private static String syntheticPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
