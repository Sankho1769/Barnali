package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import com.barnali.api.auth.OwnerBootstrapRunner;
import com.barnali.operations.OwnerBootstrapConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

class MigrationOnlyApplicationTest {
    private static final String DATABASE_URL =
            "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require";

    @Test
    void isIsolatedFromApplicationComponentScanningAndOwnerBootstrap() {
        assertThat(MigrationOnlyApplication.class.getAnnotation(SpringBootConfiguration.class)).isNotNull();
        assertThat(MigrationOnlyApplication.class.getAnnotation(SpringBootApplication.class)).isNull();
        assertThat(MigrationOnlyApplication.class.getAnnotation(ComponentScan.class)).isNull();
        assertThat(MigrationOnlyApplication.class.getAnnotation(Profile.class).value())
                .containsExactly("barnali-migration-only");
        assertThat(MigrationOnlyApplication.class.getAnnotation(ConditionalOnProperty.class).havingValue())
                .isEqualTo("true");
        assertThat(OwnerBootstrapRunner.class.getAnnotation(Component.class)).isNull();
        assertThat(OwnerBootstrapConfiguration.class.getPackageName()).doesNotStartWith("com.barnali.api");
        assertThat(OwnerBootstrapConfiguration.class.getAnnotation(Component.class)).isNull();
    }

    @Test
    void pinsFlywayToValidatedBarnaliMigrationsAndDisablesDestructiveOrImplicitSetup() {
        FluentConfiguration flyway = Flyway.configure();
        FlywayConfigurationCustomizer customizer = new MigrationOnlyApplication().migrationOnlyFlywaySettings();

        customizer.customize(flyway);

        Configuration configuration = flyway;
        assertThat(configuration.getLocations()).extracting(Object::toString).containsExactly("classpath:db/migration");
        assertThat(configuration.getSchemas()).containsExactly("barnali");
        assertThat(configuration.getDefaultSchema()).isEqualTo("barnali");
        assertThat(configuration.isCleanDisabled()).isTrue();
        assertThat(configuration.isValidateOnMigrate()).isTrue();
        assertThat(configuration.isCreateSchemas()).isFalse();
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        assertThat(configuration.isOutOfOrder()).isFalse();
    }

    @Test
    void runtimeDatasourceSelectsOnlyThePrivateBarnaliSchema() throws Exception {
        var yamlSources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        var yaml = yamlSources.getFirst();

        assertThat(yaml.getProperty("spring.datasource.hikari.schema")).isEqualTo("barnali");
        assertThat(yaml.getProperty("spring.datasource.url")).isEqualTo("${DATABASE_URL:}");
        assertThat(yaml.getProperty("spring.datasource.username")).isEqualTo("${DATABASE_USERNAME:}");
        assertThat(yaml.getProperty("spring.datasource.password")).isEqualTo("${DATABASE_PASSWORD:}");
        assertThat(yaml.getProperty("spring.flyway.enabled")).isEqualTo(false);
        assertThat(yaml.getProperty("barnali.migration.runner.enabled")).isNull();
        assertThat(yaml.getProperty("barnali.owner.bootstrap.enabled")).isNull();
        assertThat(yaml.getProperty("spring.flyway.url")).isEqualTo("${MIGRATION_DATABASE_URL:}");
        assertThat(yaml.getProperty("spring.flyway.user")).isEqualTo("${MIGRATION_DATABASE_USERNAME:}");
        assertThat(yaml.getProperty("spring.flyway.password")).isEqualTo("${MIGRATION_DATABASE_PASSWORD:}");
        assertThat(yaml.getProperty("barnali.expected-supabase-project-ref"))
                .isEqualTo("${BARNALI_EXPECTED_SUPABASE_PROJECT_REF:}");
        assertThat(yaml.getProperty("spring.flyway.schemas[0]")).isEqualTo("barnali");
        assertThat(yaml.getProperty("spring.flyway.default-schema")).isEqualTo("barnali");
        assertThat(yaml.getProperty("spring.flyway.create-schemas")).isEqualTo(false);
        assertThat(yaml.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(MigrationOnlyApplication.BARNALI_SCHEMA).isEqualTo("barnali");
    }

    @Test
    void ordinaryApiConfigurationDisablesFlywayWhileIsolatedRunnerExplicitlyEnablesIt() {
        assertThat(MigrationOnlyApplication.safeArguments(false))
                .contains("--spring.flyway.enabled=true", "--spring.profiles.active=barnali-migration-only",
                        "--barnali.migration.runner.enabled=true");
        assertThat(MigrationOnlyApplication.safeArguments(true))
                .contains("--spring.flyway.enabled=true", MigrationOnlyApplication.DIAGNOSTIC_ARGUMENT,
                        "--spring.profiles.active=barnali-migration-only", "--barnali.migration.runner.enabled=true");
    }

    @Test
    void migrationCredentialsAreRejectedByInitializerBeforeDatasourceCreation() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(new MockEnvironment()
                .withProperty("spring.datasource.url", DATABASE_URL)
                .withProperty("spring.datasource.username", "postgres.projectref1234567890")
                .withProperty("spring.datasource.password", "synthetic-runtime-secret")
                .withProperty("spring.datasource.hikari.schema", "barnali"));

        assertThatThrownBy(() -> MigrationOnlyApplication.migrationCredentialGuard().initialize(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flyway database URL")
                .hasMessageNotContaining("synthetic-runtime-secret");

        context.close();
    }

    @Test
    void diagnosticModeValidatesAndReportsPendingMigrationsWithoutCallingMigrate() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource();
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MigrationOnlyApplication application = new MigrationOnlyApplication();
        MockEnvironment environment = configuredEnvironment();
        FlywayMigrationStrategy strategy = application.checkedMigrationStrategy(environment, dataSource);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        environment.setProperty("barnali.migration.mode", "diagnostic");
        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            strategy.migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        String report = output.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("pending_migrations=1,2,3", "migration_validation=PASSED_WITH_PENDING",
                "migration_validation_pending=version=1,description=barnali community and admin",
                "migration_validation_pending=version=2,description=rate limit audit and report guard",
                "migration_validation_pending=version=3,description=row security and scoped auth operations",
                "migration_status=NOT_RUN", "database_ssl=ENFORCED", "barnali_schema=EXISTS",
                "database_role=kind=flyway,superuser=false,bypassrls=false,createdb=false,createrole=false,database_create=false,schema_exists=true",
                "database_role=kind=runtime,superuser=false,bypassrls=false,createdb=false,createrole=false,database_create=false,schema_exists=true",
                "database_principals_distinct=true", "migration_schema_owner=true", "runtime_no_ddl_privileges=true",
                "owner_provisioner_role_ready=true",
                "database_role_memberships_review=migration_memberships=0,migration_inherited_roles=0,runtime_memberships=0,runtime_inherited_roles=0",
                "database_role_memberships_clear=migration=true,runtime=true",
                "schema_provisioning_status=VERIFIED_MIGRATOR_OWNS_SCHEMA_WITHOUT_DATABASE_CREATE",
                "migration_readiness=READY", "runtime_readiness=WAITING_MIGRATIONS",
                "runtime_search_path=current_schema_is_barnali=true,first_path_schema_is_barnali=true,includes_barnali=true,includes_public=false",
                "runtime_schema_privileges=usage=true,create=false,owner=false",
                "flyway_history_table=ABSENT",
                "resolved_migration=version=1,description=barnali community and admin,state=PENDING",
                "resolved_migration=version=2,description=rate limit audit and report guard,state=PENDING",
                "resolved_migration=version=3,description=row security and scoped auth operations,state=PENDING",
                "migration_history_comparison=version=1,resolved_state=PENDING,history=ABSENT",
                "migration_history_comparison=version=2,resolved_state=PENDING,history=ABSENT",
                "migration_history_comparison=version=3,resolved_state=PENDING,history=ABSENT",
                "v1_table=users,present=false", "v1_index=users_created_at_idx,present=false",
                "v2_index=reports_one_pending_report_per_reporter_target_idx,present=false",
                "v2_column=auth_login_limits.last_audit_at,present=false",
                "v1_function=barnali_reject_audit_mutation,present=false",
                "v1_trigger=admin_audit_events_no_update,present=false",
                "v1_seed=admin_bootstrap_guard:id=1,present=false",
                "v2_duplicate_pending_report_groups=unavailable_reports_table_absent",
                "v3_column=admin_bootstrap_guard.consumed_at,present=false",
                "v3_function=provision_initial_owner,present=false",
                "v3_view=public_room_summaries,present=false",
                "v3_policies=table=users,count=0,expected=3,complete=false",
                "public_object_inventory_begin=true",
                "public_object=type=extension,schema=public,name=synthetic-test-extension",
                "public_object=type=function,schema=public,name=synthetic_test()",
                "public_object_inventory_end=true",
                "inspected_function=public.rls_auto_enable(),present=true,owner=synthetic_owner,language=plpgsql,return_type=event_trigger,arguments=(),security_definer=false,volatility=VOLATILE,extension_owned=false",
                "function_references=event_trigger=true,event_trigger_enabled=true,event_trigger_events=ddl_command_end,event_trigger_fires_create_table=true,ordinary_trigger=false,other_catalog_dependency=false",
                "function_definition_scan=create_table=NOT_DETECTED,alter_table=DETECTED_DYNAMIC_SQL,"
                        + "row_level_security=DETECTED_DYNAMIC_SQL,default_privileges=NOT_DETECTED,"
                        + "ddl_command_inspection=true,create_table_event_tag=true,schema_name_reference=true,"
                        + "schema_scope=PUBLIC_ONLY_FILTER_DETECTED,"
                        + "public_schema_filter_detected=true,dynamic_sql_detected=true",
                "rls_auto_enable_target_schema=barnali,event_trigger_enabled=true,new_table_rls_detected=true,affects_target_schema=false",
                "flyway_non_empty_candidate=type=function,schema=barnali,name=synthetic_test()");
        assertThat(report).doesNotContain("private_secret_marker", "CREATE FUNCTION public.rls_auto_enable");
        assertThat(flyway.infoCalls).isEqualTo(1);
        assertThat(flyway.validateWithResultCalls).isEqualTo(1);
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void diagnosticModeReportsRealValidationErrorSeparatelyFromPendingMigrationsAndRedactsSecrets() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource();
        ValidateResult validation = mixedPendingAndChecksumValidation();
        TrackingFlyway flyway = trackingFlyway(dataSource, validation, "2");
        MigrationOnlyApplication application = new MigrationOnlyApplication();
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        FlywayMigrationStrategy strategy = application.checkedMigrationStrategy(environment, dataSource);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            assertThatThrownBy(() -> strategy.migrate(flyway))
                    .isInstanceOf(MigrationOnlyApplication.SafeDiagnosticException.class);
        } finally {
            System.setOut(originalOut);
        }

        String report = output.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("pending_migrations=2", "migration_validation=FAILED",
                "migration_validation_pending=version=2,description=rate limit audit and report guard",
                "migration_validation_error=version=1,description=barnali community and admin,error_code=CHECKSUM_MISMATCH");
        assertThat(report).doesNotContain("secret validation detail", "secret/path", "secret validation message");
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void pendingMigrationsAreNotValidationErrors() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource();
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            new MigrationOnlyApplication().checkedMigrationStrategy(environment, dataSource).migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("pending_migrations=1,2,3", "migration_validation=PASSED_WITH_PENDING", "migration_status=NOT_RUN")
                .doesNotContain("migration_validation_error=");
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void diagnosticReportsMembershipAndInheritedStateForBothPrincipalsAndBlocksReadiness() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource(true, true);
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            new MigrationOnlyApplication().checkedMigrationStrategy(environment, dataSource).migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).contains(
                "database_role_membership=principal=flyway,member_of=synthetic_migration_parent,membership=true,inherited_privileges=true",
                "database_role_membership=principal=runtime,member_of=synthetic_runtime_parent,membership=true,inherited_privileges=false",
                "database_role_memberships_clear=migration=false,runtime=false",
                "migration_readiness=BLOCKED_ROLE_MEMBERSHIP_REVIEW",
                "runtime_readiness=BLOCKED_ROLE_MEMBERSHIP_REVIEW");
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void blocksMigrationPrincipalWhenBypassRlsIsEnabled() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource(true, false, true, false);
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            new MigrationOnlyApplication().checkedMigrationStrategy(environment, dataSource).migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).contains(
                "database_role=kind=flyway,superuser=false,bypassrls=true",
                "migration_role_bypassrls_disabled=false",
                "migration_readiness=BLOCKED_ROLE_PRIVILEGES");
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void blocksRuntimePrincipalWhenBypassRlsIsEnabled() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource(true, false, false, true);
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            new MigrationOnlyApplication().checkedMigrationStrategy(environment, dataSource).migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString(StandardCharsets.UTF_8)).contains(
                "database_role=kind=runtime,superuser=false,bypassrls=true",
                "runtime_role_bypassrls_disabled=false",
                "runtime_readiness=BLOCKED_RUNTIME_BYPASSRLS");
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void skipsFlywayValidationUntilThePrivateSchemaExists() throws Exception {
        DataSource dataSource = mockReadOnlyDataSource(false);
        TrackingFlyway flyway = trackingFlyway(dataSource, pendingOnlyValidation());
        MockEnvironment environment = configuredEnvironment().withProperty("barnali.migration.mode", "diagnostic");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try (PrintStream capturedOut = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            new MigrationOnlyApplication().checkedMigrationStrategy(environment, dataSource).migrate(flyway);
        } finally {
            System.setOut(originalOut);
        }

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("barnali_schema=ABSENT", "pending_migrations=1,2,3",
                        "runtime_search_path=current_schema_is_barnali=false,first_path_schema_is_barnali=false,includes_barnali=false,includes_public=false",
                        "migration_validation=SKIPPED_SCHEMA_ABSENT", "migration_status=NOT_RUN")
                .doesNotContain("migration_validation_error=");
        assertThat(flyway.validateWithResultCalls).isZero();
        assertThat(flyway.migrateCalls).isZero();
    }

    @Test
    void diagnosticRequestIsExactAndDoesNotNeedTheMigrationApprovalFlag() {
        assertThat(MigrationOnlyApplication.isDiagnosticOnlyRequest(
                new String[] {MigrationOnlyApplication.DIAGNOSTIC_ARGUMENT})).isTrue();
        assertThat(MigrationOnlyApplication.isDiagnosticOnlyRequest(
                new String[] {"--barnali.migration.mode=apply"})).isFalse();
    }

    private static MockEnvironment configuredEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", DATABASE_URL)
                .withProperty("spring.datasource.username", "postgres.projectref1234567890")
                .withProperty("spring.datasource.password", "synthetic-test-password")
                .withProperty("spring.datasource.hikari.schema", "barnali")
                .withProperty("spring.flyway.url", DATABASE_URL)
                .withProperty("spring.flyway.user", "barnali_migrator.projectref1234567890")
                .withProperty("spring.flyway.password", "different-synthetic-secret")
                .withProperty("barnali.expected-supabase-project-ref", "projectref1234567890");
    }

    private static DataSource mockReadOnlyDataSource() {
        return mockReadOnlyDataSource(true, false);
    }

    private static DataSource mockReadOnlyDataSource(boolean schemaExists) {
        return mockReadOnlyDataSource(schemaExists, false);
    }

    private static DataSource mockReadOnlyDataSource(boolean schemaExists, boolean includeMemberships) {
        return mockReadOnlyDataSource(schemaExists, includeMemberships, false, false);
    }

    private static DataSource mockReadOnlyDataSource(boolean schemaExists, boolean includeMemberships,
            boolean migrationBypassRls, boolean runtimeBypassRls) {
        Connection migrationConnection = mockReadOnlyConnection(
                schemaExists, 101L, includeMemberships, migrationBypassRls);
        Connection runtimeConnection = mockReadOnlyConnection(
                schemaExists, 202L, includeMemberships, runtimeBypassRls);
        AtomicInteger connections = new AtomicInteger();
        return proxy(DataSource.class, (method, args) -> {
            if (!"getConnection".equals(method.getName())) return defaultValue(method.getReturnType());
            return connections.getAndIncrement() == 0 ? migrationConnection : runtimeConnection;
        });
    }

    private static Connection mockReadOnlyConnection(boolean schemaExists, long roleOid, boolean includeMemberships) {
        return mockReadOnlyConnection(schemaExists, roleOid, includeMemberships, false);
    }

    private static Connection mockReadOnlyConnection(boolean schemaExists, long roleOid,
            boolean includeMemberships, boolean bypassRls) {
        ResultSet targetResult = resultSet(true, "postgres", schemaExists, true);
        ResultSet absentHistoryResult = resultSet(true, (Object) null);
        ResultSet historyRowsResult = resultSet(false);
        ResultSet functionResult = resultSet(true, "synthetic_owner", "plpgsql", "event_trigger", "", false, "v",
                false, true, true, true, "ddl_command_end", false, false,
                "CREATE FUNCTION public.rls_auto_enable() RETURNS event_trigger LANGUAGE plpgsql "
                        + "AS $barnali$ BEGIN FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands() "
                        + "WHERE cmd.command_tag = 'CREATE TABLE' LOOP IF cmd.schema_name = 'public' THEN "
                        + "EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', "
                        + "'private_secret_marker'); END IF; END LOOP; END; $barnali$;");
        Statement readOnlyStatement = proxy(Statement.class, (method, args) -> "execute".equals(method.getName())
                ? true : defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (method, args) -> switch (method.getName()) {
            case "createStatement" -> readOnlyStatement;
            case "prepareStatement" -> {
                String sql = (String) args[0];
                List<String> bound = new ArrayList<>();
                yield proxy(PreparedStatement.class, (preparedMethod, preparedArgs) -> {
                    if ("setString".equals(preparedMethod.getName())) {
                        int index = (Integer) preparedArgs[0] - 1;
                        while (bound.size() <= index) bound.add(null);
                        bound.set(index, (String) preparedArgs[1]);
                        return null;
                    }
                    if (!"executeQuery".equals(preparedMethod.getName())) {
                        return defaultValue(preparedMethod.getReturnType());
                    }
                    if (sql.contains("barnali_role_memberships")) {
                        if (!includeMemberships) return rowsResultSet(List.of());
                        String roleName = roleOid == 101L ? "synthetic_migration_parent" : "synthetic_runtime_parent";
                        boolean inherited = roleOid == 101L;
                        return rowsResultSet(List.<Object[]>of(new Object[] {roleName, true, inherited}));
                    }
                    if (sql.contains("barnali_database_effective_privileges")) {
                        return resultSet(true, "postgres", true, false, true);
                    }
                    if (sql.contains("barnali_schema_effective_privileges")) {
                        return rowsResultSet(List.of(new Object[] {"public", true, false},
                                new Object[] {"barnali", true, roleOid == 101L && schemaExists}));
                    }
                    if (sql.contains("barnali_relation_effective_privileges")
                            || sql.contains("barnali_sequence_effective_privileges")
                            || sql.contains("barnali_public_acl_inventory")
                            || sql.contains("barnali_routine_effective_privileges")) {
                        return rowsResultSet(List.of());
                    }
                    if (sql.contains("barnali_owner_provisioner_role")) {
                        return resultSet(true, true, false, false, false, false, false, true, true, false, true, true);
                    }
                    if (sql.contains("FROM pg_roles role_row")) {
                        boolean migrationRole = roleOid == 101L;
                        return resultSet(true, roleOid, false, bypassRls, false, false, false,
                                schemaExists, schemaExists, migrationRole && schemaExists,
                                migrationRole && schemaExists,
                                true, true);
                    }
                    if (sql.contains("current_schemas(false)")) {
                        return resultSet(true, schemaExists, schemaExists, schemaExists, false);
                    }
                    if (sql.contains("barnali_connection_target")) return resultSet(true, "postgres", true);
                    if (sql.contains("current_database()")) return targetResult;
                    if (sql.contains("to_regnamespace('barnali') IS NOT NULL")) return resultSet(true, schemaExists);
                    if (sql.contains("has_schema_privilege(current_user, 'barnali', 'USAGE')")) return resultSet(true, true);
                    if (sql.contains("has_schema_privilege(current_user, 'barnali', 'CREATE')")) return resultSet(true, false);
                    if (sql.contains("nspowner = (SELECT oid FROM pg_roles")) return resultSet(true, false);
                    if (sql.contains("barnali_runtime_table_privileges")) {
                        return rowsResultSet(bound.stream()
                                .map(name -> new Object[] {name, false, false, false, false, false,
                                        false, false, false, 0}).toList());
                    }
                    if (sql.contains("barnali_runtime_sequence_privileges")
                            || sql.contains("barnali_runtime_function_privileges")) {
                        return rowsResultSet(List.of());
                    }
                    if (sql.contains("to_regclass('barnali.flyway_schema_history') IS NOT NULL")) {
                        return resultSet(true, false, false);
                    }
                    // This catalog query includes "FROM pg_proc procedure_row"; match its marker
                    // before the broader legacy "FROM pg_proc p" check.
                    if (sql.contains("barnali_public_object_inventory")) {
                        return rowsResultSet(List.of(
                                new Object[] {"extension", "public", "synthetic-test-extension"},
                                new Object[] {"function", "public", "synthetic_test()"}));
                    }
                    if (sql.contains("FROM pg_proc p")) return resultSet(true, false);
                    if (sql.contains("FROM pg_class c")) return resultSet(true, false);
                    if (sql.contains("FROM pg_policy p")) return resultSet(true, 0);
                    if (sql.contains("to_regclass('barnali.flyway_schema_history')::text")) return absentHistoryResult;
                    if (sql.contains("barnali_flyway_non_empty_candidates")) {
                        return rowsResultSet(List.<Object[]>of(
                                new Object[] {"function", "barnali", "synthetic_test()"}));
                    }
                    if (sql.contains("barnali_rls_auto_enable_inspection")) return functionResult;
                    if (sql.contains("FROM (VALUES")) {
                        List<Object[]> rows = bound.stream().map(name -> new Object[] {name, false}).toList();
                        return rowsResultSet(rows);
                    }
                    if (sql.contains("information_schema.columns") || sql.contains("to_regprocedure")
                            || sql.contains("pg_trigger")) return resultSet(true, false);
                    if (sql.contains("flyway_schema_history")) return historyRowsResult;
                    throw new AssertionError("Unexpected diagnostic query.");
                });
            }
            default -> defaultValue(method.getReturnType());
        });
        return connection;
    }

    private static ResultSet resultSet(boolean hasRow, Object... columns) {
        return proxy(ResultSet.class, new ProxyCall() {
            private boolean first = true;

            @Override
            public Object invoke(Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> {
                        boolean value = hasRow && first;
                        first = false;
                        yield value;
                    }
                    case "getString" -> columns[((Integer) args[0]) - 1];
                    case "getBoolean" -> (Boolean) columns[((Integer) args[0]) - 1];
                    case "getLong" -> (Long) columns[((Integer) args[0]) - 1];
                    case "getInt" -> (Integer) columns[((Integer) args[0]) - 1];
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
    }

    private static ResultSet rowsResultSet(List<Object[]> rows) {
        return proxy(ResultSet.class, new ProxyCall() {
            private int row = -1;

            @Override
            public Object invoke(Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++row < rows.size();
                    case "getString" -> (String) rows.get(row)[((Integer) args[0]) - 1];
                    case "getBoolean" -> (Boolean) rows.get(row)[((Integer) args[0]) - 1];
                    case "getLong" -> (Long) rows.get(row)[((Integer) args[0]) - 1];
                    case "getInt" -> (Integer) rows.get(row)[((Integer) args[0]) - 1];
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
    }

    @FunctionalInterface
    private interface ProxyCall {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, ProxyCall call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, args) -> call.invoke(method, args));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static ValidateResult pendingOnlyValidation() {
        List<ValidateOutput> pending = List.of(
                validationOutput("1", "barnali community and admin",
                        CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED),
                validationOutput("2", "rate limit audit and report guard",
                        CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED),
                validationOutput("3", "row security and scoped auth operations",
                        CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED));
        ErrorDetails aggregate = new ErrorDetails(CoreErrorCode.VALIDATE_ERROR, "secret aggregate message");
        return new ValidateResult("postgres", null, aggregate, false, pending.size(), pending,
                List.of("secret validation text"));
    }

    private static ValidateResult mixedPendingAndChecksumValidation() {
        ErrorDetails checksum = new ErrorDetails(CoreErrorCode.CHECKSUM_MISMATCH, "secret checksum message");
        List<ValidateOutput> invalid = new ArrayList<>();
        invalid.add(validationOutput("2", "rate limit audit and report guard",
                CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED));
        invalid.add(new ValidateOutput("1", "barnali community and admin", "secret/path/V1.sql", checksum));
        ErrorDetails aggregate = new ErrorDetails(CoreErrorCode.VALIDATE_ERROR, "secret aggregate message");
        return new ValidateResult("postgres", null, aggregate, false, invalid.size(), invalid,
                List.of("secret validation text"));
    }

    private static ValidateOutput validationOutput(String version, String description, CoreErrorCode errorCode) {
        return new ValidateOutput(version, description, "secret/path/V" + version + ".sql",
                new ErrorDetails(errorCode, "secret validation detail"));
    }

    private static TrackingFlyway trackingFlyway(DataSource dataSource, ValidateResult validationResult,
            String... pendingVersions) {
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource);
        new MigrationOnlyApplication().migrationOnlyFlywaySettings().customize(configuration);
        Configuration loadedConfiguration = configuration.load().getConfiguration();
        String[] versions = pendingVersions.length == 0 ? new String[] {"1", "2", "3"} : pendingVersions;
        MigrationInfo[] pending = Arrays.stream(versions)
                .map(MigrationOnlyApplicationTest::pendingMigration).toArray(MigrationInfo[]::new);
        MigrationInfoService info = (MigrationInfoService) Proxy.newProxyInstance(
                MigrationInfoService.class.getClassLoader(), new Class<?>[] {MigrationInfoService.class},
                (proxy, method, args) -> "pending".equals(method.getName()) || "all".equals(method.getName())
                        ? pending : null);
        return new TrackingFlyway(loadedConfiguration, info, validationResult);
    }

    private static MigrationInfo pendingMigration(String version) {
        return (MigrationInfo) Proxy.newProxyInstance(
                MigrationInfo.class.getClassLoader(), new Class<?>[] {MigrationInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getVersion" -> MigrationVersion.fromVersion(version);
                    case "getDescription" -> switch (version) {
                        case "1" -> "barnali community and admin";
                        case "2" -> "rate limit audit and report guard";
                        default -> "row security and scoped auth operations";
                    };
                    case "getState" -> MigrationState.PENDING;
                    default -> null;
                });
    }

    private static final class TrackingFlyway extends Flyway {
        private final MigrationInfoService migrationInfo;
        private final ValidateResult validationResult;
        private int infoCalls;
        private int validateWithResultCalls;
        private int migrateCalls;

        TrackingFlyway(Configuration configuration, MigrationInfoService migrationInfo,
                ValidateResult validationResult) {
            super(configuration);
            this.migrationInfo = migrationInfo;
            this.validationResult = validationResult;
        }

        @Override
        public MigrationInfoService info() {
            infoCalls++;
            return migrationInfo;
        }

        @Override
        public ValidateResult validateWithResult() {
            validateWithResultCalls++;
            return validationResult;
        }

        @Override
        public MigrateResult migrate() {
            migrateCalls++;
            throw new AssertionError("Diagnostic mode must never call Flyway.migrate().");
        }
    }
}
