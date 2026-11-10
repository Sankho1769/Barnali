package com.barnali.api.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.ErrorDetails;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Minimal, non-web Spring Boot entry point. It intentionally has no component scan,
 * so application runners such as OwnerBootstrapRunner are never registered.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Profile("barnali-migration-only")
@ConditionalOnProperty(prefix = "barnali.migration.runner", name = "enabled", havingValue = "true")
public class MigrationOnlyApplication {
    static final String DIAGNOSTIC_ARGUMENT = "--barnali.migration.mode=diagnostic";
    static final String MIGRATION_ONLY_PROFILE = "--spring.profiles.active=barnali-migration-only";
    static final String BARNALI_SCHEMA = MigrationTarget.EXPECTED_SCHEMA;
    private static final String[] SAFE_ARGS = {
            MIGRATION_ONLY_PROFILE,
            "--barnali.migration.runner.enabled=true",
            "--spring.main.web-application-type=none",
            "--spring.flyway.enabled=true",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=" + BARNALI_SCHEMA,
            "--spring.flyway.default-schema=" + BARNALI_SCHEMA,
            "--spring.flyway.clean-disabled=true",
            "--spring.flyway.validate-on-migrate=true",
            "--spring.flyway.create-schemas=false",
            "--spring.flyway.baseline-on-migrate=false",
            "--logging.level.root=OFF"
    };
    private static final Map<String, String> EXPECTED_MIGRATION_DESCRIPTIONS = Map.of(
            "1", "barnali community and admin",
            "2", "rate limit audit and report guard",
            "3", "row security and scoped auth operations");
    private static final List<String> V1_TABLES = List.of(
            "users", "rooms", "room_memberships", "discussions", "replies", "discussion_votes", "reports",
            "user_activity_events", "admin_accounts", "admin_bootstrap_guard", "admin_invitations",
            "admin_audit_events", "auth_login_limits", "password_reset_tokens");
    private static final List<String> V1_INDEXES = List.of(
            "users_created_at_idx", "users_status_idx", "rooms_active_public_idx", "room_memberships_user_idx",
            "discussions_public_created_idx", "discussions_room_created_idx", "discussions_author_created_idx",
            "discussions_search_idx", "replies_discussion_created_idx", "replies_author_created_idx",
            "reports_status_created_idx", "reports_target_idx", "activity_user_time_idx", "activity_time_type_idx",
            "admin_accounts_single_owner_idx", "admin_accounts_active_role_idx", "admin_invitations_email_created_idx",
            "admin_audit_created_idx", "admin_audit_actor_idx", "admin_audit_action_idx",
            "auth_login_limits_updated_idx", "password_reset_tokens_expiry_idx");
    private static final List<String> V2_INDEXES = List.of(
            "reports_one_pending_report_per_reporter_target_idx");
    private static final List<String> V3_FUNCTIONS = List.of(
            "current_actor_id", "actor_has_permission", "is_admin_account", "actor_is_active", "is_public_author",
            "auth_principal_by_email", "register_user", "current_session_user", "record_successful_login",
            "current_password_hash", "change_current_password", "login_limit_blocked", "login_limit_record_attempt",
            "login_limit_clear", "login_limit_audit_address", "login_limit_prune", "issue_password_reset",
            "invalidate_password_reset_delivery", "complete_password_reset", "invitation_by_token", "accept_invitation",
            "create_admin_invitation", "submit_report", "discussion_vote_total", "admin_overview",
            "admin_analytics_overview", "admin_analytics_series", "user_activity_series", "owner_bootstrap_readiness",
            "provision_initial_owner");
    private static final List<String> V3_VIEWS = List.of(
            "public_room_summaries", "admin_user_rows", "admin_team_rows", "admin_room_rows", "admin_audit_rows");
    private static final Map<String, Integer> V3_POLICY_COUNTS = Map.ofEntries(
            Map.entry("users", 3), Map.entry("rooms", 3), Map.entry("room_memberships", 2),
            Map.entry("discussions", 3), Map.entry("replies", 3), Map.entry("discussion_votes", 3),
            Map.entry("reports", 2), Map.entry("user_activity_events", 1), Map.entry("admin_accounts", 2),
            Map.entry("admin_bootstrap_guard", 1), Map.entry("admin_invitations", 3),
            Map.entry("admin_audit_events", 2), Map.entry("auth_login_limits", 1),
            Map.entry("password_reset_tokens", 1));
    private static final String PUBLIC_OBJECT_INVENTORY_SQL = """
            /* barnali_public_object_inventory */
            WITH objects(object_type, schema_name, object_name) AS (
                SELECT CASE relation_row.relkind
                           WHEN 'r' THEN 'table'
                           WHEN 'p' THEN 'partitioned_table'
                           WHEN 'v' THEN 'view'
                           WHEN 'm' THEN 'materialized_view'
                           WHEN 'S' THEN 'sequence'
                           WHEN 'f' THEN 'foreign_table'
                           WHEN 'i' THEN 'index'
                           WHEN 'I' THEN 'partitioned_index'
                           WHEN 'c' THEN 'composite_relation'
                           WHEN 't' THEN 'toast_table'
                       END,
                       namespace_row.nspname::text,
                       relation_row.relname::text
                FROM pg_class relation_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = relation_row.relnamespace
                WHERE namespace_row.nspname = 'public'
                  AND relation_row.relkind IN ('r', 'p', 'v', 'm', 'S', 'f', 'i', 'I', 'c', 't')
                UNION ALL
                SELECT CASE procedure_row.prokind
                           WHEN 'p' THEN 'procedure'
                           WHEN 'a' THEN 'aggregate'
                           WHEN 'w' THEN 'window_function'
                           ELSE 'function'
                       END,
                       namespace_row.nspname::text,
                       (procedure_row.proname || '(' || pg_get_function_identity_arguments(procedure_row.oid) || ')')::text
                FROM pg_proc procedure_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = procedure_row.pronamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT CASE type_row.typtype
                           WHEN 'd' THEN 'domain'
                           WHEN 'e' THEN 'enum_type'
                           WHEN 'r' THEN 'range_type'
                           WHEN 'm' THEN 'multirange_type'
                           WHEN 'p' THEN 'pseudo_type'
                           WHEN 'c' THEN 'composite_type'
                           ELSE CASE WHEN type_row.typelem <> 0 THEN 'array_type' ELSE 'base_type' END
                       END,
                       namespace_row.nspname::text,
                       type_row.typname::text
                FROM pg_type type_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = type_row.typnamespace
                WHERE namespace_row.nspname = 'public' AND type_row.typisdefined
                UNION ALL
                SELECT 'extension', namespace_row.nspname::text, extension_row.extname::text
                FROM pg_extension extension_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = extension_row.extnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'trigger', namespace_row.nspname::text,
                       (relation_row.relname || '.' || trigger_row.tgname)::text
                FROM pg_trigger trigger_row
                JOIN pg_class relation_row ON relation_row.oid = trigger_row.tgrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid = relation_row.relnamespace
                WHERE namespace_row.nspname = 'public' AND NOT trigger_row.tgisinternal
                UNION ALL
                SELECT 'constraint', namespace_row.nspname::text,
                       (COALESCE(relation_row.relname, type_row.typname) || '.' || constraint_row.conname)::text
                FROM pg_constraint constraint_row
                LEFT JOIN pg_class relation_row ON relation_row.oid = constraint_row.conrelid
                LEFT JOIN pg_namespace relation_namespace_row ON relation_namespace_row.oid = relation_row.relnamespace
                LEFT JOIN pg_type type_row ON type_row.oid = constraint_row.contypid
                LEFT JOIN pg_namespace type_namespace_row ON type_namespace_row.oid = type_row.typnamespace
                JOIN pg_namespace namespace_row ON namespace_row.oid = COALESCE(
                    relation_namespace_row.oid, type_namespace_row.oid)
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'rule', namespace_row.nspname::text,
                       (relation_row.relname || '.' || rewrite.rulename)::text
                FROM pg_rewrite rewrite
                JOIN pg_class relation_row ON relation_row.oid = rewrite.ev_class
                JOIN pg_namespace namespace_row ON namespace_row.oid = relation_row.relnamespace
                WHERE namespace_row.nspname = 'public' AND rewrite.rulename <> '_RETURN'
                UNION ALL
                SELECT 'row_security_policy', namespace_row.nspname::text,
                       (relation_row.relname || '.' || policy.polname)::text
                FROM pg_policy policy
                JOIN pg_class relation_row ON relation_row.oid = policy.polrelid
                JOIN pg_namespace namespace_row ON namespace_row.oid = relation_row.relnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'collation', namespace_row.nspname::text, collation_row.collname::text
                FROM pg_collation collation_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = collation_row.collnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'conversion', namespace_row.nspname::text, conversion_row.conname::text
                FROM pg_conversion conversion_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = conversion_row.connamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'text_search_configuration', namespace_row.nspname::text, config_row.cfgname::text
                FROM pg_ts_config config_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = config_row.cfgnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'text_search_dictionary', namespace_row.nspname::text, dictionary_row.dictname::text
                FROM pg_ts_dict dictionary_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = dictionary_row.dictnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'text_search_parser', namespace_row.nspname::text, parser_row.prsname::text
                FROM pg_ts_parser parser_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = parser_row.prsnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'text_search_template', namespace_row.nspname::text, template_row.tmplname::text
                FROM pg_ts_template template_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = template_row.tmplnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'operator_class', namespace_row.nspname::text, opclass_row.opcname::text
                FROM pg_opclass opclass_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = opclass_row.opcnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'operator_family', namespace_row.nspname::text, opfamily_row.opfname::text
                FROM pg_opfamily opfamily_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = opfamily_row.opfnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'operator', namespace_row.nspname::text,
                       (operator_row.oprname || '(' ||
                        CASE WHEN operator_row.oprleft = 0 THEN 'NONE'
                             ELSE format_type(operator_row.oprleft, NULL) END || ',' ||
                        CASE WHEN operator_row.oprright = 0 THEN 'NONE'
                             ELSE format_type(operator_row.oprright, NULL) END || ')')::text
                FROM pg_operator operator_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = operator_row.oprnamespace
                WHERE namespace_row.nspname = 'public'
                UNION ALL
                SELECT 'extended_statistics', namespace_row.nspname::text, statistics_row.stxname::text
                FROM pg_statistic_ext statistics_row
                JOIN pg_namespace namespace_row ON namespace_row.oid = statistics_row.stxnamespace
                WHERE namespace_row.nspname = 'public'
            )
            SELECT object_type, schema_name, object_name
            FROM objects
            ORDER BY object_type, schema_name, object_name
            """;
    private static final String FLYWAY_NON_EMPTY_CANDIDATES_SQL = """
            /* barnali_flyway_non_empty_candidates */
            WITH candidates(object_type, schema_name, object_name) AS (
                SELECT CASE relation_row.relkind
                           WHEN 'r' THEN 'table'
                           WHEN 'v' THEN 'view'
                           WHEN 'S' THEN 'sequence'
                           WHEN 't' THEN 'toast_table'
                       END,
                       namespace_row.nspname::text,
                       relation_row.relname::text
                FROM pg_catalog.pg_class relation_row
                JOIN pg_catalog.pg_namespace namespace_row ON namespace_row.oid = relation_row.relnamespace
                LEFT JOIN pg_catalog.pg_depend dependency_row
                    ON dependency_row.objid = relation_row.oid AND dependency_row.deptype = 'e'
                WHERE namespace_row.nspname = 'barnali' AND dependency_row.objid IS NULL
                  AND relation_row.relkind IN ('r', 'v', 'S', 't')
                UNION ALL
                SELECT CASE type_row.typtype
                           WHEN 'd' THEN 'domain'
                           WHEN 'e' THEN 'enum_type'
                           WHEN 'r' THEN 'range_type'
                           WHEN 'm' THEN 'multirange_type'
                           ELSE 'base_type'
                       END,
                       namespace_row.nspname::text,
                       type_row.typname::text
                FROM pg_catalog.pg_type type_row
                JOIN pg_catalog.pg_namespace namespace_row ON namespace_row.oid = type_row.typnamespace
                LEFT JOIN pg_catalog.pg_depend dependency_row
                    ON dependency_row.objid = type_row.oid AND dependency_row.deptype = 'e'
                WHERE namespace_row.nspname = 'barnali' AND dependency_row.objid IS NULL
                  AND type_row.typcategory NOT IN ('A', 'C')
                UNION ALL
                SELECT CASE procedure_row.prokind
                           WHEN 'p' THEN 'procedure'
                           WHEN 'a' THEN 'aggregate'
                           WHEN 'w' THEN 'window_function'
                           ELSE 'function'
                       END,
                       namespace_row.nspname::text,
                       (procedure_row.proname || '(' ||
                        pg_get_function_identity_arguments(procedure_row.oid) || ')')::text
                FROM pg_catalog.pg_proc procedure_row
                JOIN pg_catalog.pg_namespace namespace_row ON namespace_row.oid = procedure_row.pronamespace
                LEFT JOIN pg_catalog.pg_depend dependency_row
                    ON dependency_row.objid = procedure_row.oid AND dependency_row.deptype = 'e'
                WHERE namespace_row.nspname = 'barnali' AND dependency_row.objid IS NULL
            )
            SELECT object_type, schema_name, object_name
            FROM candidates
            ORDER BY object_type, schema_name, object_name
            """;
    private static final String RLS_AUTO_ENABLE_INSPECTION_SQL = """
            /* barnali_rls_auto_enable_inspection */
            SELECT owner_role.rolname::text,
                   language_row.lanname::text,
                   pg_get_function_result(function_row.oid)::text,
                   pg_get_function_identity_arguments(function_row.oid)::text,
                   function_row.prosecdef,
                   function_row.provolatile::text,
                   EXISTS (
                       SELECT 1 FROM pg_depend extension_dependency
                       WHERE extension_dependency.classid = 'pg_proc'::regclass
                         AND extension_dependency.objid = function_row.oid
                         AND extension_dependency.refclassid = 'pg_extension'::regclass
                         AND extension_dependency.deptype = 'e'
                   ),
                   EXISTS (
                       SELECT 1 FROM pg_event_trigger event_trigger_row
                       WHERE event_trigger_row.evtfoid = function_row.oid
                   ),
                   EXISTS (
                       SELECT 1 FROM pg_event_trigger event_trigger_row
                       WHERE event_trigger_row.evtfoid = function_row.oid
                         AND event_trigger_row.evtenabled IN ('O', 'A')
                   ),
                   EXISTS (
                       SELECT 1 FROM pg_event_trigger event_trigger_row
                       WHERE event_trigger_row.evtfoid = function_row.oid
                         AND event_trigger_row.evtenabled IN ('O', 'A')
                         AND event_trigger_row.evtevent = 'ddl_command_end'
                         AND (event_trigger_row.evttags IS NULL
                              OR 'CREATE TABLE' = ANY(event_trigger_row.evttags))
                   ),
                   COALESCE((
                       SELECT string_agg(DISTINCT event_trigger_row.evtevent, ',' ORDER BY event_trigger_row.evtevent)
                       FROM pg_event_trigger event_trigger_row
                       WHERE event_trigger_row.evtfoid = function_row.oid
                         AND event_trigger_row.evtenabled IN ('O', 'A')
                   ), 'none')::text,
                   EXISTS (
                       SELECT 1 FROM pg_trigger trigger_row
                       WHERE trigger_row.tgfoid = function_row.oid AND NOT trigger_row.tgisinternal
                   ),
                   EXISTS (
                       SELECT 1 FROM pg_depend dependent_object
                       WHERE dependent_object.refclassid = 'pg_proc'::regclass
                         AND dependent_object.refobjid = function_row.oid
                         AND dependent_object.classid NOT IN (
                             'pg_event_trigger'::regclass, 'pg_trigger'::regclass)
                   ),
                   pg_get_functiondef(function_row.oid)::text
            FROM pg_proc function_row
            JOIN pg_namespace namespace_row ON namespace_row.oid = function_row.pronamespace
            JOIN pg_roles owner_role ON owner_role.oid = function_row.proowner
            JOIN pg_language language_row ON language_row.oid = function_row.prolang
            WHERE function_row.oid = to_regprocedure('public.rls_auto_enable()')
            """;
    private static volatile MigrationPhase currentFailurePhase = MigrationPhase.SPRING_CONTEXT_INITIALIZATION;

    private enum MigrationPhase {
        SPRING_CONTEXT_INITIALIZATION,
        MIGRATION_PREFLIGHT,
        READ_ONLY_DIAGNOSTIC,
        FLYWAY_EXECUTION,
        POST_MIGRATION_HANDLING,
        STRATEGY_COMPLETE,
        APPLICATION_CONTEXT_READY,
        APPLICATION_CONTEXT_SHUTDOWN
    }

    public static void main(String[] arguments) {
        currentFailurePhase = MigrationPhase.SPRING_CONTEXT_INITIALIZATION;
        boolean diagnosticOnly = isDiagnosticOnlyRequest(arguments);
        if (!diagnosticOnly && !"true".equalsIgnoreCase(System.getenv("BARNALI_MIGRATION_APPROVED"))) {
            System.err.println("migration_status=REFUSED");
            System.err.println("detail=Run scripts/migrate-api.ps1 and complete its interactive approval prompt.");
            System.exit(2);
            return;
        }
        SpringApplication application = new SpringApplication(MigrationOnlyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        // Reject absent/invalid migration settings before Flyway or any datasource
        // bean can be created and potentially select the primary runtime datasource.
        application.addInitializers(migrationCredentialGuard());
        ConfigurableApplicationContext context;
        try {
            context = application.run(safeArguments(diagnosticOnly));
            currentFailurePhase = MigrationPhase.APPLICATION_CONTEXT_READY;
        } catch (Throwable failure) {
            reportFailureAndExit(failure);
            return;
        }
        try {
            currentFailurePhase = MigrationPhase.APPLICATION_CONTEXT_SHUTDOWN;
            context.close();
        } catch (Throwable failure) {
            reportFailureAndExit(failure);
        }
    }

    private static void reportFailureAndExit(Throwable failure) {
        System.err.println("migration_status=FAILED");
        System.err.println(MigrationFailureDiagnostics.format(failure, currentFailurePhase.name()));
        System.exit(1);
    }

    static ApplicationContextInitializer<ConfigurableApplicationContext> migrationCredentialGuard() {
        return context -> {
            currentFailurePhase = MigrationPhase.MIGRATION_PREFLIGHT;
            MigrationEnvironmentValidator.validate(context.getEnvironment());
        };
    }

    @Bean
    FlywayConfigurationCustomizer migrationOnlyFlywaySettings() {
        return configuration -> configuration
                .locations("classpath:db/migration")
                .schemas(BARNALI_SCHEMA)
                .defaultSchema(BARNALI_SCHEMA)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .createSchemas(false)
                .baselineOnMigrate(false)
                .outOfOrder(false);
    }

    @Bean
    FlywayMigrationStrategy checkedMigrationStrategy(Environment environment, DataSource runtimeDataSource) {
        return flyway -> {
            currentFailurePhase = MigrationPhase.MIGRATION_PREFLIGHT;
            ValidatedMigrationTarget target = MigrationEnvironmentValidator.validate(environment);
            validateEffectiveFlywayConfiguration(flyway.getConfiguration());

            System.out.println("migration_target=" + target.sanitizedSummary());
            System.out.println("configured_project_scope=CONSISTENT");
            LivePreflight livePreflight = verifyLiveTargetAndSsl(
                    flyway.getConfiguration().getDataSource(), runtimeDataSource);
            System.out.println("database_ssl=ENFORCED");

            if (!isDiagnosticOnly(environment)) {
                requireMigrationSchemaAccess(flyway.getConfiguration().getDataSource());
                requireDistinctNonDdlRuntimeRole(livePreflight);
                if (!livePreflight.ownerProvisionerRoleReady()) {
                    throw new IllegalStateException("The execute-only owner-provisioner role is missing or over-privileged.");
                }
            }

            var migrationInfo = flyway.info();
            MigrationInfo[] pending = migrationInfo.pending();
            String versions = Arrays.stream(pending)
                    .map(MigrationInfo::getVersion)
                    .filter(version -> version != null)
                    .map(Object::toString)
                    .collect(Collectors.joining(","));
            System.out.println("pending_migrations=" + (versions.isEmpty() ? "none" : versions));
            printResolvedMigrationMetadata(migrationInfo.all(), livePreflight.history());

            if (isDiagnosticOnly(environment)) {
                currentFailurePhase = MigrationPhase.READ_ONLY_DIAGNOSTIC;
                runReadOnlyFlywayDiagnostics(flyway, pending, livePreflight.barnaliSchemaExists());
                currentFailurePhase = MigrationPhase.STRATEGY_COMPLETE;
                return;
            }

            currentFailurePhase = MigrationPhase.FLYWAY_EXECUTION;
            System.out.println("migration_phase=FLYWAY_EXECUTION");
            MigrateResult result = flyway.migrate();
            currentFailurePhase = MigrationPhase.POST_MIGRATION_HANDLING;
            if (!result.success) {
                throw new IllegalStateException("Flyway did not report a successful migration result.");
            }
            System.out.println("migration_status=SUCCESS");
            System.out.println("migrations_executed=" + result.migrationsExecuted);
            System.out.println("migration_schema=" + BARNALI_SCHEMA);
            currentFailurePhase = MigrationPhase.POST_MIGRATION_HANDLING;
            reportPostMigrationMetadata(runtimeDataSource, livePreflight.migrationRoleOid());
            currentFailurePhase = MigrationPhase.STRATEGY_COMPLETE;
        };
    }

    static void runReadOnlyFlywayDiagnostics(Flyway flyway, MigrationInfo[] pending, boolean barnaliSchemaExists) {
        System.out.println("migration_mode=DIAGNOSTIC_ONLY");
        if (!barnaliSchemaExists) {
            System.out.println("migration_validation=SKIPPED_SCHEMA_ABSENT");
            System.out.println("migration_status=NOT_RUN");
            return;
        }
        ValidateResult result = flyway.validateWithResult();
        Map<String, String> pendingByVersion = pendingDescriptions(pending);
        List<ValidateOutput> pendingValidationEntries = new ArrayList<>();
        List<ValidateOutput> actualValidationErrors = new ArrayList<>();
        List<ValidateOutput> invalidMigrations = result.invalidMigrations == null
                ? List.of() : result.invalidMigrations;
        for (ValidateOutput invalid : invalidMigrations) {
            if (isExpectedPendingValidation(invalid, pendingByVersion)) {
                pendingValidationEntries.add(invalid);
            } else {
                actualValidationErrors.add(invalid);
            }
        }

        for (ValidateOutput invalid : pendingValidationEntries) {
            String version = safeVersion(invalid.version);
            String description = safeDescription(version, invalid.description);
            System.out.println("migration_validation_pending=version=" + version + ",description=" + description);
        }
        for (ValidateOutput invalid : actualValidationErrors) {
            String version = safeVersion(invalid.version);
            String description = safeDescription(version, invalid.description);
            String errorCode = safeErrorCode(invalid.errorDetails);
            if ("none".equals(errorCode)) {
                errorCode = "unavailable";
            }
            System.out.println("migration_validation_error=version=" + version
                    + ",description=" + description + ",error_code=" + errorCode);
        }

        boolean onlyExpectedPending = !pendingValidationEntries.isEmpty() && actualValidationErrors.isEmpty()
                && (result.errorDetails == null || result.errorDetails.errorCode == CoreErrorCode.VALIDATE_ERROR);
        boolean validationPassed = result.validationSuccessful || onlyExpectedPending;
        String validationStatus = result.validationSuccessful ? "PASSED"
                : onlyExpectedPending ? "PASSED_WITH_PENDING" : "FAILED";
        System.out.println("migration_validation=" + validationStatus);

        if (!validationPassed) {
            String globalErrorCode = safeErrorCode(result.errorDetails);
            System.out.println("migration_validation_error_code="
                    + ("none".equals(globalErrorCode) ? "unavailable" : globalErrorCode));
            throw new SafeDiagnosticException(null);
        }
        System.out.println("migration_status=NOT_RUN");
    }

    private static Map<String, String> pendingDescriptions(MigrationInfo[] pending) {
        Map<String, String> pendingByVersion = new HashMap<>();
        if (pending == null) {
            return pendingByVersion;
        }
        for (MigrationInfo migration : pending) {
            if (migration == null || migration.getVersion() == null) {
                continue;
            }
            String version = safeVersion(migration.getVersion().toString());
            String description = safeDescription(version, migration.getDescription());
            if (!"unrecognized".equals(version) && !"unrecognized".equals(description)) {
                pendingByVersion.put(version, description);
            }
        }
        return pendingByVersion;
    }

    private static boolean isExpectedPendingValidation(ValidateOutput invalid, Map<String, String> pendingByVersion) {
        if (invalid == null || invalid.errorDetails == null
                || invalid.errorDetails.errorCode != CoreErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED) {
            return false;
        }
        String version = safeVersion(invalid.version);
        return pendingByVersion.containsKey(version)
                && pendingByVersion.get(version).equals(safeDescription(version, invalid.description));
    }

    private static void printResolvedMigrationMetadata(MigrationInfo[] migrations, Map<String, Boolean> history) {
        for (MigrationInfo migration : migrations) {
            if (migration == null || migration.getVersion() == null) {
                continue;
            }
            String version = safeVersion(migration.getVersion().toString());
            if (!EXPECTED_MIGRATION_DESCRIPTIONS.containsKey(version)) {
                continue;
            }
            String description = safeDescription(version, migration.getDescription());
            String state = migration.getState() == null ? "unavailable" : migration.getState().name();
            if (!state.matches("[A-Z_]{1,40}")) {
                state = "unavailable";
            }
            System.out.println("resolved_migration=version=" + version
                    + ",description=" + description + ",state=" + state);
            String historyState = history.containsKey(version)
                    ? (Boolean.TRUE.equals(history.get(version)) ? "APPLIED" : "FAILED") : "ABSENT";
            System.out.println("migration_history_comparison=version=" + version
                    + ",resolved_state=" + state + ",history=" + historyState);
        }
    }

    private static String safeVersion(String version) {
        return version != null && version.matches("[0-9]+(?:\\.[0-9]+)*") && version.length() <= 24
                ? version : "unrecognized";
    }

    private static String safeDescription(String version, String description) {
        String expected = EXPECTED_MIGRATION_DESCRIPTIONS.get(version);
        return expected != null && expected.equals(description) ? expected : "unrecognized";
    }

    private static String safeErrorCode(ErrorDetails details) {
        if (details == null || !(details.errorCode instanceof CoreErrorCode code)) {
            return "none";
        }
        return code.name();
    }

    static boolean isDiagnosticOnlyRequest(String[] arguments) {
        return arguments != null && Arrays.asList(arguments).contains(DIAGNOSTIC_ARGUMENT);
    }

    private static boolean isDiagnosticOnly(Environment environment) {
        return "diagnostic".equalsIgnoreCase(environment.getProperty("barnali.migration.mode"));
    }

    static String[] safeArguments(boolean diagnosticOnly) {
        if (!diagnosticOnly) {
            return SAFE_ARGS.clone();
        }
        String[] arguments = Arrays.copyOf(SAFE_ARGS, SAFE_ARGS.length + 1);
        arguments[SAFE_ARGS.length] = DIAGNOSTIC_ARGUMENT;
        return arguments;
    }

    private static void validateEffectiveFlywayConfiguration(Configuration configuration) {
        if (!configuration.isCleanDisabled() || !configuration.isValidateOnMigrate()
                || configuration.isCreateSchemas() || configuration.isBaselineOnMigrate()
                || !BARNALI_SCHEMA.equals(configuration.getDefaultSchema())
                || !Arrays.equals(configuration.getSchemas(), new String[] {BARNALI_SCHEMA})
                || configuration.getLocations().length != 1
                || !"classpath:db/migration".equals(configuration.getLocations()[0].toString())) {
            throw new IllegalStateException("Effective Flyway settings do not match the guarded migration-only configuration.");
        }
    }

    private static void requireMigrationSchemaAccess(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var readOnly = connection.createStatement()) {
                    readOnly.execute("SET TRANSACTION READ ONLY");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT to_regnamespace('barnali') IS NOT NULL,
                               CASE WHEN to_regnamespace('barnali') IS NULL THEN FALSE
                                    ELSE has_schema_privilege(current_user, 'barnali', 'USAGE') END,
                               CASE WHEN to_regnamespace('barnali') IS NULL THEN FALSE
                                    ELSE has_schema_privilege(current_user, 'barnali', 'CREATE') END,
                               CASE WHEN to_regnamespace('barnali') IS NULL THEN FALSE
                                    ELSE (SELECT nspowner = (SELECT oid FROM pg_roles WHERE rolname=current_user)
                                          FROM pg_namespace WHERE nspname='barnali') END,
                               has_database_privilege(current_user, current_database(), 'CREATE'),
                               (SELECT rolsuper FROM pg_roles WHERE rolname=current_user),
                               (SELECT rolcreatedb FROM pg_roles WHERE rolname=current_user),
                               (SELECT rolcreaterole FROM pg_roles WHERE rolname=current_user),
                               NOT EXISTS (
                                   SELECT 1 FROM pg_namespace other_schema
                                   WHERE other_schema.nspname <> 'barnali'
                                     AND other_schema.nspname <> 'information_schema'
                                     AND other_schema.nspname !~ '^pg_'
                                     AND has_schema_privilege(current_user, other_schema.oid, 'CREATE')
                               )
                        """); ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new IllegalStateException(
                                "The private BARNALI schema is absent; create it explicitly before applying migrations.");
                    }
                    if (!result.getBoolean(2) || !result.getBoolean(3) || !result.getBoolean(4)) {
                        throw new IllegalStateException(
                                "The migration principal must own the private BARNALI schema and have USAGE and CREATE on it.");
                    }
                    if (result.getBoolean(5) || result.getBoolean(6) || result.getBoolean(7)
                            || result.getBoolean(8) || !result.getBoolean(9)) {
                        throw new IllegalStateException(
                                "The migration principal must be non-superuser and have DDL rights only in the BARNALI schema.");
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new SafeDatabaseException(exception.getSQLState());
        }
    }

    private static void requireDistinctNonDdlRuntimeRole(LivePreflight preflight) {
        if (!preflight.rolesAreDistinct()) {
            throw new IllegalStateException("The migration and runtime connections resolve to the same database principal.");
        }
        if (!preflight.migrationHasNoRoleMemberships() || !preflight.runtimeHasNoRoleMemberships()) {
            throw new IllegalStateException("Both database principals must have no role memberships before migration apply.");
        }
        if (!preflight.runtimeHasNoDdlPrivileges()) {
            throw new IllegalStateException("The runtime principal must not own schemas or have database/schema CREATE privileges.");
        }
        if (!preflight.runtimeSearchPathTargetsBarnali()) {
            throw new IllegalStateException("The runtime connection search path does not target the private BARNALI schema.");
        }
    }

    private static LivePreflight verifyLiveTargetAndSsl(DataSource dataSource, DataSource runtimeDataSource) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var readOnly = connection.createStatement()) {
                    readOnly.execute("SET TRANSACTION READ ONLY");
                }
                boolean barnaliSchemaExists;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT current_database(), to_regnamespace('barnali') IS NOT NULL,
                               (SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid())
                        """); ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("The read-only database target check returned no result.");
                    }
                    String database = result.getString(1);
                    barnaliSchemaExists = result.getBoolean(2);
                    boolean ssl = result.getBoolean(3);
                    if (!MigrationTarget.EXPECTED_DATABASE.equals(database)) {
                        throw new IllegalStateException("The live database does not match the approved BARNALI target.");
                    }
                    if (!ssl) {
                        throw new IllegalStateException("The PostgreSQL session did not report SSL; no migrations were applied.");
                    }
                    System.out.println("database_connection=kind=flyway,expected_database=true,ssl=true");
                }
                System.out.println("barnali_schema=" + (barnaliSchemaExists ? "EXISTS" : "ABSENT"));
                RuntimeReadiness runtimeReadiness = reportDatabaseRoles(connection, runtimeDataSource);
                reportExpectedSchemaObjects(connection);
                reportPublicObjectInventory(connection);
                RlsAssessment rlsAssessment = reportRlsAutoEnableFunction(connection);
                reportFlywayNonEmptyCandidates(connection);
                return new LivePreflight(reportFlywayHistory(connection), barnaliSchemaExists,
                        runtimeReadiness.searchPathTargetsBarnali(), runtimeReadiness.rolesAreDistinct(),
                        runtimeReadiness.runtimeHasNoDdlPrivileges(), runtimeReadiness.migrationRoleOid(),
                        runtimeReadiness.migrationHasNoRoleMemberships(), runtimeReadiness.runtimeHasNoRoleMemberships(),
                        rlsAssessment.affectsBarnali(), rlsAssessment.scopeUnverified(),
                        runtimeReadiness.ownerProvisionerRoleReady());
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new SafeDatabaseException(exception.getSQLState());
        }
    }

    private record LivePreflight(Map<String, Boolean> history, boolean barnaliSchemaExists,
            boolean runtimeSearchPathTargetsBarnali, boolean rolesAreDistinct,
            boolean runtimeHasNoDdlPrivileges, long migrationRoleOid,
            boolean migrationHasNoRoleMemberships, boolean runtimeHasNoRoleMemberships,
            boolean rlsAutoEnableAffectsBarnali, boolean rlsScopeUnverified,
            boolean ownerProvisionerRoleReady) {}

    private static RuntimeReadiness reportDatabaseRoles(Connection migrationConnection, DataSource runtimeDataSource)
            throws SQLException {
        RoleSnapshot migrationRole = inspectCurrentRole(migrationConnection);
        RoleSnapshot runtimeRole;
        boolean runtimeSearchPathTargetsBarnali;
        ConnectionTarget runtimeTarget;
        RuntimeObjectPrivileges runtimePrivileges;
        DatabasePrivilegeDiagnostics.RoleMembershipSummary migrationMemberships =
                DatabasePrivilegeDiagnostics.report(migrationConnection, "flyway", System.out);
        boolean ownerProvisionerRoleReady = reportOwnerProvisionerRole(migrationConnection);
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var readOnly = connection.createStatement()) {
                    readOnly.execute("SET TRANSACTION READ ONLY");
                }
                runtimeTarget = inspectConnectionTarget(connection, "runtime");
                runtimeRole = inspectCurrentRole(connection);
                DatabasePrivilegeDiagnostics.RoleMembershipSummary runtimeMemberships =
                        DatabasePrivilegeDiagnostics.report(connection, "runtime", System.out);
                runtimeSearchPathTargetsBarnali = reportRuntimeSearchPath(connection);
                runtimePrivileges = reportRuntimeObjectPrivileges(connection, migrationRole.roleOid());
                return buildRuntimeReadiness(migrationRole, runtimeRole, migrationMemberships, runtimeMemberships,
                        runtimeSearchPathTargetsBarnali, runtimePrivileges, runtimeTarget, ownerProvisionerRoleReady);
            } finally {
                connection.rollback();
            }
        }
    }

    private static RuntimeReadiness buildRuntimeReadiness(RoleSnapshot migrationRole, RoleSnapshot runtimeRole,
            DatabasePrivilegeDiagnostics.RoleMembershipSummary migrationMemberships,
            DatabasePrivilegeDiagnostics.RoleMembershipSummary runtimeMemberships,
            boolean runtimeSearchPathTargetsBarnali, RuntimeObjectPrivileges runtimePrivileges,
            ConnectionTarget runtimeTarget, boolean ownerProvisionerRoleReady) {

        if (!MigrationTarget.EXPECTED_DATABASE.equals(runtimeTarget.database()) || !runtimeTarget.ssl()) {
            throw new IllegalStateException("The runtime connection target or SSL state does not match the approved target.");
        }
        System.out.println("database_ssl=runtime_ENFORCED");

        printRoleSnapshot("flyway", migrationRole);
        printRoleSnapshot("runtime", runtimeRole);
        boolean rolesAreDistinct = runtimeRole.roleOid() != migrationRole.roleOid()
                && migrationRole.sessionUser() && runtimeRole.sessionUser();
        boolean runtimeHasNoDdlPrivileges = !runtimeRole.superuser() && !runtimeRole.createDatabase()
                && !runtimeRole.createRole() && !runtimeRole.databaseCreate()
                && runtimeRole.noCreateOnOtherSchemas()
                && (!runtimeRole.schemaExists() || (!runtimeRole.schemaCreate() && !runtimeRole.schemaOwner()));
        System.out.println("database_principals_distinct=" + rolesAreDistinct);
        System.out.println("database_role_memberships_review="
                + "migration_memberships=" + migrationMemberships.memberships()
                + ",migration_inherited_roles=" + migrationMemberships.inheritedRoles()
                + ",runtime_memberships=" + runtimeMemberships.memberships()
                + ",runtime_inherited_roles=" + runtimeMemberships.inheritedRoles());
        boolean migrationHasNoRoleMemberships = migrationMemberships.hasNoMemberships();
        boolean runtimeHasNoRoleMemberships = runtimeMemberships.hasNoMemberships();
        System.out.println("database_role_memberships_clear="
                + "migration=" + migrationHasNoRoleMemberships + ",runtime=" + runtimeHasNoRoleMemberships);
        System.out.println("owner_provisioner_role_ready=" + ownerProvisionerRoleReady);
        System.out.println("migration_schema_owner="
                + (migrationRole.schemaExists() ? migrationRole.schemaOwner() : "unavailable_schema_absent"));
        String provisioningStatus = SchemaProvisioningStatus.assess(migrationRole.schemaExists(),
                migrationRole.schemaOwner(), migrationRole.databaseCreate());
        System.out.println("schema_provisioning_status=" + provisioningStatus);
        System.out.println("runtime_no_ddl_privileges=" + runtimeHasNoDdlPrivileges);
        System.out.println("runtime_rls_mode=" + (runtimeRole.superuser() ? "SUPERUSER"
                : runtimeRole.bypassRls() ? "BYPASSRLS_TEMPORARY" : "SUBJECT_TO_RLS"));
        System.out.println("migration_role_bypassrls_disabled=" + !migrationRole.bypassRls());
        System.out.println("runtime_role_bypassrls_disabled=" + !runtimeRole.bypassRls());
        boolean migrationRolePrivilegesAreRestricted = !migrationRole.superuser() && !migrationRole.bypassRls()
                && !migrationRole.createDatabase()
                && !migrationRole.createRole() && !migrationRole.databaseCreate()
                && migrationRole.noCreateOnOtherSchemas();
        String migrationReadiness = !rolesAreDistinct ? "BLOCKED_SHARED_PRINCIPAL"
                : !migrationHasNoRoleMemberships ? "BLOCKED_ROLE_MEMBERSHIP_REVIEW"
                : !ownerProvisionerRoleReady ? "BLOCKED_OWNER_PROVISIONER_ROLE"
                : !migrationRolePrivilegesAreRestricted ? "BLOCKED_ROLE_PRIVILEGES"
                : !migrationRole.schemaExists() ? "WAITING_AUTHORIZED_OPERATOR_SCHEMA_PROVISIONING"
                : migrationRole.schemaOwner() && migrationRole.schemaUsage() && migrationRole.schemaCreate()
                        && migrationRolePrivilegesAreRestricted
                        ? "READY" : "BLOCKED_ROLE_PRIVILEGES";
        String runtimeReadiness = !runtimeHasNoDdlPrivileges ? "BLOCKED_RUNTIME_DDL_PRIVILEGES"
                : runtimeRole.bypassRls() ? "BLOCKED_RUNTIME_BYPASSRLS"
                : !runtimeSearchPathTargetsBarnali ? "BLOCKED_SEARCH_PATH"
                : !runtimeHasNoRoleMemberships ? "BLOCKED_ROLE_MEMBERSHIP_REVIEW"
                : !runtimePrivileges.schemaExists() ? "WAITING_SCHEMA_CREATION"
                : !runtimePrivileges.schemaUsage() ? "BLOCKED_SCHEMA_USAGE"
                : runtimePrivileges.tablesPresent() < V1_TABLES.size() ? "WAITING_MIGRATIONS"
                : !runtimePrivileges.flywayHistoryNotExposed() ? "BLOCKED_FLYWAY_HISTORY_EXPOSED"
                : runtimeRole.bypassRls() ? "TRANSITIONAL_BYPASSRLS_PRIVILEGES_REVIEW_REQUIRED"
                : runtimePrivileges.tablesWithoutPolicies() > 0 ? "BLOCKED_RLS_DEFAULT_DENY"
                : "RLS_POLICIES_PRESENT_SEMANTIC_REVIEW_REQUIRED";
        System.out.println("migration_readiness=" + migrationReadiness);
        System.out.println("runtime_readiness=" + runtimeReadiness);
        return new RuntimeReadiness(runtimeSearchPathTargetsBarnali, rolesAreDistinct,
                runtimeHasNoDdlPrivileges, migrationRole.roleOid(), migrationHasNoRoleMemberships,
                runtimeHasNoRoleMemberships, ownerProvisionerRoleReady);
    }

    private static ConnectionTarget inspectConnectionTarget(Connection connection, String kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                /* barnali_connection_target */
                SELECT current_database(),
                       COALESCE((SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()), FALSE)
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("The read-only connection target check returned no result.");
            }
            String database = result.getString(1);
            boolean ssl = result.getBoolean(2);
            boolean expectedDatabase = MigrationTarget.EXPECTED_DATABASE.equals(database);
            System.out.println("database_connection=kind=" + kind
                    + ",database=" + (expectedDatabase ? MigrationTarget.EXPECTED_DATABASE : "unexpected")
                    + ",expected_database=" + expectedDatabase + ",ssl=" + ssl);
            return new ConnectionTarget(database, ssl);
        }
    }

    private static RuntimeObjectPrivileges reportRuntimeObjectPrivileges(Connection connection, long migrationRoleOid)
            throws SQLException {
        boolean schemaExists = queryBoolean(connection, "SELECT to_regnamespace('barnali') IS NOT NULL");
        boolean schemaUsage = schemaExists
                && queryBoolean(connection, "SELECT has_schema_privilege(current_user, 'barnali', 'USAGE')");
        int tablesPresent = 0;
        int tablesWithoutRls = 0;
        int tablesWithoutPolicies = 0;
        String valueRows = V1_TABLES.stream().map(ignored -> "(?)").collect(Collectors.joining(","));
        String tableSql = """
                /* barnali_runtime_table_privileges */
                WITH expected(table_name) AS (VALUES %s)
                SELECT expected.table_name, table_row.oid IS NOT NULL,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE has_table_privilege(current_user, table_row.oid, 'SELECT') END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE has_table_privilege(current_user, table_row.oid, 'INSERT') END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE has_table_privilege(current_user, table_row.oid, 'UPDATE') END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE has_table_privilege(current_user, table_row.oid, 'DELETE') END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE table_row.relrowsecurity END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE table_row.relforcerowsecurity END,
                       CASE WHEN table_row.oid IS NULL THEN FALSE ELSE table_row.relowner = ?::oid END,
                       CASE WHEN table_row.oid IS NULL THEN 0 ELSE
                           (SELECT count(*) FROM pg_policy policy WHERE policy.polrelid = table_row.oid) END
                FROM expected
                LEFT JOIN pg_namespace schema_row ON schema_row.nspname = 'barnali'
                LEFT JOIN pg_class table_row ON table_row.relnamespace = schema_row.oid
                     AND table_row.relname = expected.table_name AND table_row.relkind IN ('r','p')
                ORDER BY expected.table_name
                """.formatted(valueRows);
        try (PreparedStatement statement = connection.prepareStatement(tableSql)) {
            for (int index = 0; index < V1_TABLES.size(); index++) {
                statement.setString(index + 1, V1_TABLES.get(index));
            }
            statement.setLong(V1_TABLES.size() + 1, migrationRoleOid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String table = safeCatalogIdentifier(result.getString(1));
                    boolean exists = result.getBoolean(2);
                    boolean rlsEnabled = result.getBoolean(7);
                    int policies = result.getInt(10);
                    if (exists) tablesPresent++;
                    if (exists && !rlsEnabled) tablesWithoutRls++;
                    if (exists && rlsEnabled && policies == 0) tablesWithoutPolicies++;
                    System.out.println("runtime_table_privilege=table=" + table
                            + ",present=" + exists
                            + ",select=" + result.getBoolean(3)
                            + ",insert=" + result.getBoolean(4)
                            + ",update=" + result.getBoolean(5)
                            + ",delete=" + result.getBoolean(6)
                            + ",rls_enabled=" + rlsEnabled
                            + ",force_rls=" + result.getBoolean(8)
                            + ",owner_matches_migrator=" + result.getBoolean(9)
                            + ",policies=" + policies);
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                /* barnali_runtime_sequence_privileges */
                SELECT sequence_row.relname,
                       has_sequence_privilege(current_user, sequence_row.oid, 'USAGE'),
                       has_sequence_privilege(current_user, sequence_row.oid, 'SELECT'),
                       has_sequence_privilege(current_user, sequence_row.oid, 'UPDATE'),
                       sequence_row.relowner = ?::oid
                FROM pg_class sequence_row
                JOIN pg_namespace schema_row ON schema_row.oid = sequence_row.relnamespace
                WHERE schema_row.nspname = 'barnali' AND sequence_row.relkind = 'S'
                ORDER BY sequence_row.relname
                """)) {
            statement.setLong(1, migrationRoleOid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    System.out.println("runtime_sequence_privilege=sequence="
                            + safeCatalogIdentifier(result.getString(1))
                            + ",usage=" + result.getBoolean(2)
                            + ",select=" + result.getBoolean(3)
                            + ",update=" + result.getBoolean(4)
                            + ",owner_matches_migrator=" + result.getBoolean(5));
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                /* barnali_runtime_function_privileges */
                SELECT procedure_row.proname || '(' || pg_get_function_identity_arguments(procedure_row.oid) || ')',
                       has_function_privilege(current_user, procedure_row.oid, 'EXECUTE'),
                       procedure_row.proowner = ?::oid
                FROM pg_proc procedure_row
                JOIN pg_namespace schema_row ON schema_row.oid = procedure_row.pronamespace
                WHERE schema_row.nspname = 'barnali'
                ORDER BY procedure_row.proname
                """)) {
            statement.setLong(1, migrationRoleOid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    System.out.println("runtime_function_privilege=function="
                            + safeCatalogIdentifier(result.getString(1))
                            + ",execute=" + result.getBoolean(2)
                            + ",owner_matches_migrator=" + result.getBoolean(3));
                }
            }
        }
        System.out.println("runtime_schema_exists=" + schemaExists);
        System.out.println("runtime_schema_privileges=usage=" + schemaUsage
                + ",create=" + (schemaExists && queryBoolean(connection,
                        "SELECT has_schema_privilege(current_user, 'barnali', 'CREATE')"))
                + ",owner=" + (schemaExists && queryBoolean(connection, """
                        SELECT nspowner = (SELECT oid FROM pg_roles WHERE rolname=current_user)
                        FROM pg_namespace WHERE nspname='barnali'
                        """)));
        boolean flywayHistoryNotExposed;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT to_regclass('barnali.flyway_schema_history') IS NOT NULL,
                       CASE WHEN to_regclass('barnali.flyway_schema_history') IS NULL THEN FALSE
                            ELSE has_table_privilege(current_user, 'barnali.flyway_schema_history', 'SELECT') END
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Flyway-history access could not be inspected.");
            boolean historyPresent = result.getBoolean(1);
            flywayHistoryNotExposed = !historyPresent || !result.getBoolean(2);
            System.out.println("runtime_flyway_history_access=present=" + historyPresent
                    + ",select=" + (historyPresent && result.getBoolean(2))
                    + ",private_from_runtime=" + flywayHistoryNotExposed);
        }
        return new RuntimeObjectPrivileges(schemaExists, schemaUsage, tablesPresent, tablesWithoutRls,
                tablesWithoutPolicies, flywayHistoryNotExposed);
    }

    private static void reportPostMigrationMetadata(DataSource runtimeDataSource, long migrationRoleOid) {
        try (Connection connection = runtimeDataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (var readOnly = connection.createStatement()) {
                    readOnly.execute("SET TRANSACTION READ ONLY");
                }
                ConnectionTarget target = inspectConnectionTarget(connection, "post_migration_runtime");
                if (!target.ssl() || !MigrationTarget.EXPECTED_DATABASE.equals(target.database())) {
                    throw new IllegalStateException("Post-migration runtime target or SSL verification failed.");
                }
                boolean searchPath = reportRuntimeSearchPath(connection);
                RuntimeObjectPrivileges privileges = reportRuntimeObjectPrivileges(connection, migrationRoleOid);
                String rlsStatus = !privileges.schemaExists() || !privileges.schemaUsage() || privileges.tablesPresent() == 0
                        ? "SCHEMA_OR_TABLES_ABSENT"
                        : privileges.tablesPresent() != V1_TABLES.size() ? "EXPECTED_TABLES_INCOMPLETE"
                        : !privileges.flywayHistoryNotExposed() ? "FLYWAY_HISTORY_EXPOSED_TO_RUNTIME"
                        : privileges.tablesWithoutRls() > 0 ? "RLS_NOT_ENABLED_ON_ALL_TABLES" : "RLS_ENABLED_ON_ALL_TABLES";
                System.out.println("post_migration_rls_status=" + rlsStatus);
                System.out.println("post_migration_search_path=" + (searchPath ? "BARNALI" : "INVALID"));
                System.out.println("post_migration_runtime_readiness=" + (searchPath && "RLS_ENABLED_ON_ALL_TABLES".equals(rlsStatus)
                        ? "REVIEW_PRIVILEGES_AND_POLICIES" : "BLOCKED"));
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new SafeDatabaseException(exception.getSQLState());
        }
    }

    private record ConnectionTarget(String database, boolean ssl) {}
    private record RuntimeObjectPrivileges(boolean schemaExists, boolean schemaUsage, int tablesPresent,
            int tablesWithoutRls, int tablesWithoutPolicies, boolean flywayHistoryNotExposed) {}

    private static boolean reportRuntimeSearchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT current_schema() = 'barnali',
                       COALESCE((current_schemas(false))[1] = 'barnali', FALSE),
                       'barnali' = ANY(current_schemas(false)),
                       'public' = ANY(current_schemas(false))
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("The read-only runtime search-path inspection returned no result.");
            }
            boolean currentSchemaBarnali = result.getBoolean(1);
            boolean firstPathSchemaBarnali = result.getBoolean(2);
            boolean includesBarnali = result.getBoolean(3);
            boolean includesPublic = result.getBoolean(4);
            System.out.println("runtime_search_path=current_schema_is_barnali=" + currentSchemaBarnali
                    + ",first_path_schema_is_barnali=" + firstPathSchemaBarnali
                    + ",includes_barnali=" + includesBarnali
                    + ",includes_public=" + includesPublic);
            return currentSchemaBarnali && firstPathSchemaBarnali && includesBarnali && !includesPublic;
        }
    }

    private record RuntimeReadiness(boolean searchPathTargetsBarnali, boolean rolesAreDistinct,
            boolean runtimeHasNoDdlPrivileges, long migrationRoleOid,
            boolean migrationHasNoRoleMemberships, boolean runtimeHasNoRoleMemberships,
            boolean ownerProvisionerRoleReady) {}

    private static boolean reportOwnerProvisionerRole(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                /* barnali_owner_provisioner_role */
                WITH provisioner AS (SELECT * FROM pg_roles WHERE rolname='barnali_provisioner')
                SELECT EXISTS(SELECT 1 FROM provisioner),
                       COALESCE((SELECT rolsuper FROM provisioner),TRUE),
                       COALESCE((SELECT rolbypassrls FROM provisioner),TRUE),
                       COALESCE((SELECT rolcreatedb FROM provisioner),TRUE),
                       COALESCE((SELECT rolcreaterole FROM provisioner),TRUE),
                       COALESCE((SELECT has_database_privilege(oid,current_database(),'CREATE') FROM provisioner),TRUE),
                       NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM provisioner)),
                       NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname<>'information_schema'
                         AND n.nspname !~ '^pg_' AND has_schema_privilege((SELECT oid FROM provisioner),n.oid,'CREATE')),
                       COALESCE((SELECT n.nspowner=(SELECT oid FROM provisioner) FROM pg_namespace n WHERE n.nspname='barnali'),FALSE),
                       NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                         WHERE n.nspname='barnali' AND c.relkind IN ('r','p','v','m','f')
                           AND (has_table_privilege((SELECT oid FROM provisioner),c.oid,'SELECT')
                             OR has_table_privilege((SELECT oid FROM provisioner),c.oid,'INSERT')
                             OR has_table_privilege((SELECT oid FROM provisioner),c.oid,'UPDATE')
                             OR has_table_privilege((SELECT oid FROM provisioner),c.oid,'DELETE'))),
                       NOT EXISTS (SELECT 1 FROM pg_class s JOIN pg_namespace n ON n.oid=s.relnamespace
                         WHERE n.nspname='barnali' AND s.relkind='S'
                           AND (has_sequence_privilege((SELECT oid FROM provisioner),s.oid,'USAGE')
                             OR has_sequence_privilege((SELECT oid FROM provisioner),s.oid,'SELECT')
                             OR has_sequence_privilege((SELECT oid FROM provisioner),s.oid,'UPDATE')))
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("The owner-provisioner role inspection returned no result.");
                boolean safe = result.getBoolean(1) && !result.getBoolean(2) && !result.getBoolean(3)
                        && !result.getBoolean(4) && !result.getBoolean(5) && !result.getBoolean(6)
                        && result.getBoolean(7) && result.getBoolean(8) && !result.getBoolean(9)
                        && result.getBoolean(10) && result.getBoolean(11);
                System.out.println("owner_provisioner_role=present=" + result.getBoolean(1)
                        + ",superuser=" + result.getBoolean(2) + ",bypassrls=" + result.getBoolean(3)
                        + ",createdb=" + result.getBoolean(4) + ",createrole=" + result.getBoolean(5)
                        + ",database_create=" + result.getBoolean(6) + ",no_role_memberships=" + result.getBoolean(7)
                        + ",no_schema_create=" + result.getBoolean(8) + ",owns_barnali_schema=" + result.getBoolean(9)
                        + ",no_table_privileges=" + result.getBoolean(10) + ",no_sequence_privileges=" + result.getBoolean(11));
                return safe;
            }
        }
    }

    private static RoleSnapshot inspectCurrentRole(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role_row.oid::bigint, role_row.rolsuper, role_row.rolbypassrls,
                       role_row.rolcreatedb, role_row.rolcreaterole,
                       has_database_privilege(role_row.oid, current_database(), 'CREATE'),
                       schema_row.oid IS NOT NULL,
                       CASE WHEN schema_row.oid IS NULL THEN NULL
                            ELSE has_schema_privilege(role_row.oid, schema_row.oid, 'USAGE') END,
                       CASE WHEN schema_row.oid IS NULL THEN NULL
                            ELSE has_schema_privilege(role_row.oid, schema_row.oid, 'CREATE') END,
                       CASE WHEN schema_row.oid IS NULL THEN NULL
                            ELSE schema_row.nspowner = role_row.oid END,
                       current_user = session_user,
                       NOT EXISTS (
                           SELECT 1 FROM pg_namespace other_schema
                           WHERE other_schema.nspname <> 'barnali'
                             AND other_schema.nspname <> 'information_schema'
                             AND other_schema.nspname !~ '^pg_'
                             AND has_schema_privilege(role_row.oid, other_schema.oid, 'CREATE')
                       )
                FROM pg_roles role_row
                LEFT JOIN pg_namespace schema_row ON schema_row.nspname = 'barnali'
                WHERE role_row.rolname = current_user
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("The read-only database role inspection returned no result.");
            }
            boolean schemaExists = result.getBoolean(7);
            boolean schemaUsage = schemaExists && result.getBoolean(8);
            boolean schemaCreate = schemaExists && result.getBoolean(9);
            boolean schemaOwner = schemaExists && result.getBoolean(10);
            return new RoleSnapshot(result.getLong(1), result.getBoolean(2), result.getBoolean(3),
                    result.getBoolean(4), result.getBoolean(5), result.getBoolean(6), schemaExists,
                    schemaUsage, schemaCreate, schemaOwner, result.getBoolean(11), result.getBoolean(12));
        }
    }

    private static void printRoleSnapshot(String kind, RoleSnapshot role) {
        System.out.println("database_role=kind=" + kind
                + ",superuser=" + role.superuser()
                + ",bypassrls=" + role.bypassRls()
                + ",createdb=" + role.createDatabase()
                + ",createrole=" + role.createRole()
                + ",database_create=" + role.databaseCreate()
                + ",schema_exists=" + role.schemaExists()
                + ",schema_usage=" + (role.schemaExists() ? role.schemaUsage() : "unavailable_schema_absent")
                + ",schema_create=" + (role.schemaExists() ? role.schemaCreate() : "unavailable_schema_absent")
                + ",schema_owner=" + (role.schemaExists() ? role.schemaOwner() : "unavailable_schema_absent")
                + ",no_create_on_other_schemas=" + role.noCreateOnOtherSchemas()
                + ",session_user=" + role.sessionUser());
    }

    private record RoleSnapshot(long roleOid, boolean superuser, boolean bypassRls, boolean createDatabase,
            boolean createRole, boolean databaseCreate,
            boolean schemaExists, boolean schemaUsage, boolean schemaCreate, boolean schemaOwner,
            boolean sessionUser, boolean noCreateOnOtherSchemas) {}

    private static Map<String, Boolean> reportFlywayHistory(Connection connection) throws SQLException {
        Map<String, Boolean> history = new HashMap<>();
        String historyTable;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_regclass('barnali.flyway_schema_history')::text");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("The read-only Flyway history check returned no result.");
            }
            historyTable = result.getString(1);
        }
        if (historyTable == null) {
            System.out.println("flyway_history_table=ABSENT");
            return history;
        }
        System.out.println("flyway_history_table=EXISTS");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT version, description, success
                FROM barnali.flyway_schema_history
                WHERE version IN ('1', '2', '3')
                ORDER BY installed_rank
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String version = safeVersion(result.getString(1));
                String description = safeDescription(version, result.getString(2));
                boolean success = result.getBoolean(3);
                if (!"unrecognized".equals(version)) {
                    history.put(version, success);
                }
                System.out.println("flyway_history=version=" + version
                        + ",description=" + description + ",success=" + success);
            }
        }
        return history;
    }

    private static void reportExpectedSchemaObjects(Connection connection) throws SQLException {
        Map<String, Boolean> tables = reportRelations(connection, "v1_table", V1_TABLES);
        reportRelations(connection, "v1_index", V1_INDEXES);
        reportRelations(connection, "v2_index", V2_INDEXES);

        boolean v2AuditColumn = queryBoolean(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'barnali' AND table_name = 'auth_login_limits'
                      AND column_name = 'last_audit_at'
                )
                """);
        System.out.println("v2_column=auth_login_limits.last_audit_at,present=" + v2AuditColumn);

        boolean auditFunction = queryBoolean(connection,
                "SELECT to_regprocedure('barnali.barnali_reject_audit_mutation()') IS NOT NULL");
        System.out.println("v1_function=barnali_reject_audit_mutation,present=" + auditFunction);

        boolean auditTrigger = queryBoolean(connection, """
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger trigger_row
                    JOIN pg_class table_row ON table_row.oid = trigger_row.tgrelid
                    JOIN pg_namespace schema_row ON schema_row.oid = table_row.relnamespace
                    WHERE schema_row.nspname = 'barnali' AND table_row.relname = 'admin_audit_events'
                      AND trigger_row.tgname = 'admin_audit_events_no_update' AND NOT trigger_row.tgisinternal
                )
                """);
        System.out.println("v1_trigger=admin_audit_events_no_update,present=" + auditTrigger);

        boolean guardSeed = Boolean.TRUE.equals(tables.get("admin_bootstrap_guard"))
                && queryBoolean(connection, "SELECT EXISTS (SELECT 1 FROM barnali.admin_bootstrap_guard WHERE id = 1)");
        System.out.println("v1_seed=admin_bootstrap_guard:id=1,present=" + guardSeed);

        if (Boolean.TRUE.equals(tables.get("reports"))) {
            long duplicateGroups = queryLong(connection, """
                    SELECT COUNT(*)
                    FROM (
                        SELECT 1 FROM barnali.reports
                        WHERE status = 'PENDING'
                        GROUP BY reporter_id, target_type, target_id
                        HAVING COUNT(*) > 1
                    ) duplicate_groups
                    """);
            System.out.println("v2_duplicate_pending_report_groups=" + duplicateGroups);
        } else {
            System.out.println("v2_duplicate_pending_report_groups=unavailable_reports_table_absent");
        }
        reportV3SchemaObjects(connection);
    }

    private static void reportV3SchemaObjects(Connection connection) throws SQLException {
        boolean consumedColumn = queryBoolean(connection, """
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema='barnali' AND table_name='admin_bootstrap_guard' AND column_name='consumed_at')
                """);
        System.out.println("v3_column=admin_bootstrap_guard.consumed_at,present=" + consumedColumn);
        for (String function : V3_FUNCTIONS) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                        WHERE n.nspname='barnali' AND p.proname=?)
                    """)) {
                statement.setString(1, function);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("V3 function inventory returned no result.");
                    System.out.println("v3_function=" + function + ",present=" + result.getBoolean(1));
                }
            }
        }
        for (String view : V3_VIEWS) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                        WHERE n.nspname='barnali' AND c.relname=? AND c.relkind='v')
                    """)) {
                statement.setString(1, view);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("V3 view inventory returned no result.");
                    System.out.println("v3_view=" + view + ",present=" + result.getBoolean(1));
                }
            }
        }
        for (Map.Entry<String, Integer> expected : V3_POLICY_COUNTS.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT count(*) FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='barnali' AND c.relname=?
                    """)) {
                statement.setString(1, expected.getKey());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("V3 policy inventory returned no result.");
                    int found = result.getInt(1);
                    System.out.println("v3_policies=table=" + expected.getKey() + ",count=" + found
                            + ",expected=" + expected.getValue() + ",complete=" + (found == expected.getValue()));
                }
            }
        }
    }

    private static void reportPublicObjectInventory(Connection connection) throws SQLException {
        System.out.println("public_object_inventory_begin=true");
        try (PreparedStatement statement = connection.prepareStatement(PUBLIC_OBJECT_INVENTORY_SQL);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String objectType = safeCatalogIdentifier(result.getString(1));
                String schemaName = safeCatalogIdentifier(result.getString(2));
                String objectName = safeCatalogIdentifier(result.getString(3));
                System.out.println("public_object=type=" + objectType
                        + ",schema=" + schemaName + ",name=" + objectName);
            }
        }
        System.out.println("public_object_inventory_end=true");
    }

    private static void reportFlywayNonEmptyCandidates(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FLYWAY_NON_EMPTY_CANDIDATES_SQL);
             ResultSet result = statement.executeQuery()) {
            boolean foundCandidate = false;
            while (result.next()) {
                foundCandidate = true;
                String objectType = safeCatalogIdentifier(result.getString(1));
                String schemaName = safeCatalogIdentifier(result.getString(2));
                String objectName = safeCatalogIdentifier(result.getString(3));
                System.out.println("flyway_non_empty_candidate=type=" + objectType
                        + ",schema=" + schemaName + ",name=" + objectName);
            }
            if (!foundCandidate) {
                System.out.println("flyway_non_empty_candidate=none");
            }
        }
    }

    private static RlsAssessment reportRlsAutoEnableFunction(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RLS_AUTO_ENABLE_INSPECTION_SQL);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                System.out.println("inspected_function=public.rls_auto_enable(),present=false");
                return new RlsAssessment(false, false, false, "UNAVAILABLE");
            }

            String owner = safeCatalogIdentifier(result.getString(1));
            String language = safeMetadataValue(result.getString(2));
            String returnType = safeMetadataValue(result.getString(3));
            String arguments = safeMetadataValue(result.getString(4));
            boolean securityDefiner = result.getBoolean(5);
            String volatility = volatilityName(result.getString(6));
            boolean extensionOwned = result.getBoolean(7);
            boolean eventTriggerReference = result.getBoolean(8);
            boolean eventTriggerEnabled = result.getBoolean(9);
            boolean eventTriggerFiresCreateTable = result.getBoolean(10);
            String eventTriggerEvents = safeMetadataValue(result.getString(11));
            boolean triggerReference = result.getBoolean(12);
            boolean otherCatalogDependency = result.getBoolean(13);
            String definition = result.getString(14);

            System.out.println("inspected_function=public.rls_auto_enable(),present=true"
                    + ",owner=" + owner
                    + ",language=" + language
                    + ",return_type=" + returnType
                    + ",arguments=(" + arguments + ")"
                    + ",security_definer=" + securityDefiner
                    + ",volatility=" + volatility
                    + ",extension_owned=" + extensionOwned);
            System.out.println("function_references=event_trigger=" + eventTriggerReference
                    + ",event_trigger_enabled=" + eventTriggerEnabled
                    + ",event_trigger_events=" + eventTriggerEvents
                    + ",event_trigger_fires_create_table=" + eventTriggerFiresCreateTable
                    + ",ordinary_trigger=" + triggerReference
                    + ",other_catalog_dependency=" + otherCatalogDependency);

            // Keep the retrieved definition in this stack frame only. Never log or return it.
            FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(definition);
            boolean rlsOnCreate = ("DETECTED_DYNAMIC_SQL".equals(summary.rowLevelSecurity())
                    || "DETECTED_STATIC_SQL".equals(summary.rowLevelSecurity()))
                    && summary.ddlCommandInspectionDetected() && summary.createTableEventTagDetected();
            Boolean affectsBarnali = summary.appliesToSchema(BARNALI_SCHEMA);
            System.out.println("function_definition_scan=create_table=" + summary.tableCreation()
                    + ",alter_table=" + summary.tableAlteration()
                    + ",row_level_security=" + summary.rowLevelSecurity()
                    + ",default_privileges=" + summary.defaultPrivileges()
                    + ",ddl_command_inspection=" + summary.ddlCommandInspectionDetected()
                    + ",create_table_event_tag=" + summary.createTableEventTagDetected()
                    + ",schema_name_reference=" + summary.schemaNameReferenceDetected()
                    + ",schema_scope=" + summary.schemaScope()
                    + ",public_schema_filter_detected=" + summary.publicSchemaFilterDetected()
                    + ",dynamic_sql_detected=" + summary.dynamicSqlDetected());
            System.out.println("rls_auto_enable_target_schema=" + BARNALI_SCHEMA
                    + ",event_trigger_enabled=" + eventTriggerEnabled
                    + ",new_table_rls_detected=" + rlsOnCreate
                    + ",affects_target_schema=" + (affectsBarnali == null ? "unverified" : affectsBarnali));
            boolean active = eventTriggerReference && eventTriggerEnabled
                    && eventTriggerFiresCreateTable && rlsOnCreate;
            boolean affects = active && Boolean.TRUE.equals(affectsBarnali);
            boolean scopeUnverified = active && affectsBarnali == null;
            return new RlsAssessment(active, affects, scopeUnverified, summary.schemaScope());
        }
    }

    private record RlsAssessment(boolean activeEventTrigger, boolean affectsBarnali,
            boolean scopeUnverified, String schemaScope) {}

    private static String safeMetadataValue(String value) {
        if (value == null || value.length() > 512
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            return "unavailable";
        }
        return value;
    }

    private static String volatilityName(String value) {
        if (value == null || value.length() != 1) {
            return "unavailable";
        }
        return switch (value) {
            case "i" -> "IMMUTABLE";
            case "s" -> "STABLE";
            case "v" -> "VOLATILE";
            default -> "unavailable";
        };
    }

    private static String safeCatalogIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            return "unavailable";
        }
        return value;
    }

    private static Map<String, Boolean> reportRelations(Connection connection, String category, List<String> names)
            throws SQLException {
        Map<String, Boolean> presence = new LinkedHashMap<>();
        String valueRows = names.stream().map(ignored -> "(?)").collect(Collectors.joining(","));
        String sql = "SELECT expected.name, to_regclass('barnali.' || expected.name) IS NOT NULL "
                + "FROM (VALUES " + valueRows + ") AS expected(name) ORDER BY expected.name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < names.size(); index++) {
                statement.setString(index + 1, names.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(1);
                    if (names.contains(name)) {
                        boolean exists = result.getBoolean(2);
                        presence.put(name, exists);
                        System.out.println(category + "=" + name + ",present=" + exists);
                    }
                }
            }
        }
        return presence;
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("A read-only schema inspection query returned no result.");
            }
            return result.getBoolean(1);
        }
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("A read-only aggregate inspection query returned no result.");
            }
            return result.getLong(1);
        }
    }

    static final class SafeDatabaseException extends RuntimeException {
        private final String sqlState;

        SafeDatabaseException(String sqlState) {
            super("A database preflight query failed.");
            this.sqlState = sqlState;
        }

        String sqlState() {
            return sqlState == null ? "unavailable" : sqlState;
        }
    }

    static final class SafeDiagnosticException extends RuntimeException {
        SafeDiagnosticException(Throwable cause) {
            super("The read-only Flyway validation failed.", cause);
        }
    }
}
