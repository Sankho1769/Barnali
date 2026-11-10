package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.resource.Resource;
import org.flywaydb.core.extensibility.MigrationType;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;
import org.flywaydb.core.internal.jdbc.Results;
import org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException;
import org.flywaydb.core.internal.sqlscript.SqlScriptExecutor;
import org.flywaydb.core.internal.sqlscript.SqlStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;

class MigrationFailureDiagnosticsTest {
    @Test
    void reportsTheDeepestCauseSqlStateAndMigrationLocationWithoutSensitiveText() {
        String password = "do-not-print-this-password";
        String hmac = "do-not-print-this-hmac-key";
        String jdbcUrl = "jdbc:postgresql://user:" + password + "@db.example.test/postgres";
        String sql = "INSERT INTO private_table VALUES ('private-row-value')";
        SQLException sqlFailure = new SQLException("password=" + password + " " + jdbcUrl, "23505");
        Resource resource = new TestResource("V2__private_description.sql", jdbcUrl);
        SqlStatement statement = new TestStatement(sql);
        FlywaySqlScriptException scriptFailure = new FlywaySqlScriptException(
                resource, statement, sqlFailure, "default");
        BeanCreationException outerFailure = new BeanCreationException(
                "private-bean-name", "HMAC=" + hmac + " JDBC=" + jdbcUrl, scriptFailure);

        String report = MigrationFailureDiagnostics.format(outerFailure);

        assertThat(report).contains("exception_chain=BeanCreationException>FlywaySqlScriptException>SQLException");
        assertThat(report).contains("root_cause_class=SQLException", "sqlstate=23505");
        assertThat(report).contains("flyway_error_code=DB_CONNECTION");
        assertThat(report).contains("migration_version=2", "migration_line=47");
        assertThat(report).doesNotContain(password, hmac, jdbcUrl, sql, "private_description", "private-bean-name");
        assertThat(report).doesNotContain("password=", "HMAC=", "INSERT INTO", "stacktrace");

        String preflightSummary = MigrationFailureDiagnostics.formatExceptionSummary(outerFailure);
        assertThat(preflightSummary).contains("exception_chain=BeanCreationException>FlywaySqlScriptException>SQLException",
                "root_cause_class=SQLException", "sqlstate=23505");
        assertThat(preflightSummary).doesNotContain("migration_version=", "migration_line=", password, hmac, jdbcUrl, sql);
    }

    @Test
    void reportsValidatedFlywayErrorCodeAndExecutionPhaseWithoutMessages() {
        FlywayException failure = new FlywayException("private JDBC URL and SQL text", CoreErrorCode.CONFIGURATION);

        String report = MigrationFailureDiagnostics.format(failure, "FLYWAY_EXECUTION");

        assertThat(report).contains("flyway_error_code=CONFIGURATION",
                "flyway_general_error_code=CONFIGURATION", "flyway_sql_error_code=unavailable",
                "flyway_migration_error_code=unavailable", "migration_phase=FLYWAY_EXECUTION");
        assertThat(report).doesNotContain("private JDBC URL", "SQL text");
        assertThat(MigrationFailureDiagnostics.format(failure, "private-secret-stage"))
                .contains("migration_phase=unavailable")
                .doesNotContain("private-secret-stage");
        assertThat(MigrationFailureDiagnostics.format(failure, "APPLICATION_CONTEXT_SHUTDOWN"))
                .contains("migration_phase=APPLICATION_CONTEXT_SHUTDOWN");
    }

    @Test
    void handlesMissingFailureMetadataWithFixedSafePlaceholders() {
        RuntimeException failure = new RuntimeException("secret message");

        String report = MigrationFailureDiagnostics.format(failure);

        assertThat(report).contains("exception_chain=RuntimeException", "root_cause_class=RuntimeException");
        assertThat(report).contains("sqlstate=unavailable", "migration_version=unavailable", "migration_line=unavailable");
        assertThat(report).doesNotContain("secret message");
    }

    @Test
    void preservesOnlyValidatedSqlStateFromTheExistingSafeDatabaseWrapper() {
        String report = MigrationFailureDiagnostics.format(
                new MigrationOnlyApplication.SafeDatabaseException("08001"));
        String invalidReport = MigrationFailureDiagnostics.format(
                new MigrationOnlyApplication.SafeDatabaseException("password"));

        assertThat(report).contains("root_cause_class=SafeDatabaseException", "sqlstate=08001");
        assertThat(invalidReport).contains("sqlstate=unavailable");
        assertThat(invalidReport).doesNotContain("password");
    }

    @Test
    void readsVersionFromFlywayMigrationFailureWithoutEchoingItsMessageOrScriptName() {
        MigrationType migrationType = (MigrationType) Proxy.newProxyInstance(
                MigrationType.class.getClassLoader(), new Class<?>[] {MigrationType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> "SQL";
                    case "isBaseline", "isUndo", "isSynthetic" -> false;
                    default -> defaultValue(method.getReturnType());
                });
        MigrationInfo migration = (MigrationInfo) Proxy.newProxyInstance(
                MigrationInfo.class.getClassLoader(), new Class<?>[] {MigrationInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getVersion" -> MigrationVersion.fromVersion("2");
                    case "getType" -> migrationType;
                    case "getScript" -> "V2__private_description.sql";
                    case "getDescription" -> "private description";
                    default -> defaultValue(method.getReturnType());
                });
        FlywayMigrateException failure = new FlywayMigrateException(
                migration, "private message containing a JDBC URL", false, new MigrateResult());

        String report = MigrationFailureDiagnostics.format(failure);

        assertThat(report).contains("exception_chain=FlywayMigrateException",
                "root_cause_class=FlywayMigrateException", "migration_version=2", "migration_line=unavailable");
        assertThat(report).doesNotContain("private message", "JDBC URL", "private_description", "private description");
    }

    @Test
    void prefersMigrationClassificationAndSqlStateOverGenericFlywayWrapperCode() {
        MigrationType migrationType = (MigrationType) Proxy.newProxyInstance(
                MigrationType.class.getClassLoader(), new Class<?>[] {MigrationType.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isBaseline", "isUndo", "isSynthetic" -> false;
                    default -> null;
                });
        MigrationInfo migration = (MigrationInfo) Proxy.newProxyInstance(
                MigrationInfo.class.getClassLoader(), new Class<?>[] {MigrationInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getVersion" -> MigrationVersion.fromVersion("2");
                    case "getType" -> migrationType;
                    case "getScript" -> "V2__private_description.sql";
                    default -> defaultValue(method.getReturnType());
                });
        SQLException sqlFailure = new SQLException("private database detail", "23505");
        FlywayMigrateException failure = new FlywayMigrateException(
                migration, false, sqlFailure, true, new MigrateResult());

        String report = MigrationFailureDiagnostics.format(failure);

        assertThat(report).contains("exception_chain=FlywayMigrateException>SQLException",
                "flyway_error_code=FAILED_VERSIONED_MIGRATION",
                "flyway_migration_error_code=FAILED_VERSIONED_MIGRATION",
                "sqlstate=23505", "migration_version=2", "migration_line=unavailable");
        assertThat(report).doesNotContain("private database detail", "V2__private_description.sql");
    }

    private record TestResource(String filename, String privatePath) implements Resource {
        @Override
        public String getAbsolutePath() {
            return privatePath;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return privatePath;
        }

        @Override
        public String getRelativePath() {
            return privatePath;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    private record TestStatement(String sql) implements SqlStatement {
        @Override
        public int getLineNumber() {
            return 47;
        }

        @Override
        public String getSql() {
            return sql;
        }

        @Override
        public String getDelimiter() {
            return ";";
        }

        @Override
        public boolean canExecuteInTransaction() {
            return true;
        }

        @Override
        public boolean isBatchable() {
            return false;
        }

        @Override
        public Results execute(JdbcTemplate jdbcTemplate, SqlScriptExecutor executor, Configuration configuration) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }
}
