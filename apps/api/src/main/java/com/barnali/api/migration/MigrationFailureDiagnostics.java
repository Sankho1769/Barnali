package com.barnali.api.migration;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.resource.Resource;
import org.flywaydb.core.internal.exception.FlywayMigrateException;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException;

/**
 * Formats migration failures using allow-listed diagnostic metadata only.
 * Exception messages, stack traces, SQL statements and resource paths are never read.
 */
public final class MigrationFailureDiagnostics {
    private static final int MAX_CAUSE_DEPTH = 64;
    private static final Pattern SQL_STATE = Pattern.compile("[A-Za-z0-9]{5}");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
            "(?i)^V([0-9]+(?:[._][0-9]+)*)__[A-Za-z0-9_.-]*\\.sql$");
    private static final Pattern FLYWAY_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Set<String> SAFE_PHASES = Set.of(
            "SPRING_CONTEXT_INITIALIZATION", "MIGRATION_PREFLIGHT", "READ_ONLY_DIAGNOSTIC",
            "FLYWAY_EXECUTION", "POST_MIGRATION_HANDLING", "STRATEGY_COMPLETE",
            "APPLICATION_CONTEXT_READY", "APPLICATION_CONTEXT_SHUTDOWN");

    private MigrationFailureDiagnostics() {
    }

    /** Returns one sanitized, multi-line report suitable for stderr. */
    public static String format(Throwable failure) {
        List<String> classNames = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        String sqlState = "unavailable";
        String migrationVersion = "unavailable";
        String flywayErrorCode = "unavailable";
        String migrationErrorCode = "unavailable";
        String sqlErrorCode = "unavailable";
        String generalFlywayErrorCode = "unavailable";
        int migrationLine = -1;
        Throwable current = failure;

        while (current != null && classNames.size() < MAX_CAUSE_DEPTH && visited.add(current)) {
            classNames.add(safeClassName(current.getClass()));

            if (current instanceof MigrationOnlyApplication.SafeDatabaseException safeFailure) {
                sqlState = validatedSqlState(safeFailure.sqlState(), sqlState);
            }
            if (current instanceof SQLException sqlException) {
                sqlState = validatedSqlState(sqlException.getSQLState(), sqlState);
            }
            if (current instanceof FlywaySqlException flywaySqlException) {
                sqlState = validatedSqlState(flywaySqlException.getSqlState(), sqlState);
                sqlErrorCode = safeFlywayErrorCode(flywaySqlException.getSubErrorCode(), sqlErrorCode);
            }
            if (current instanceof FlywayException flywayException) {
                generalFlywayErrorCode = safeFlywayErrorCode(
                        flywayException.getErrorCode(), generalFlywayErrorCode);
            }
            if (current instanceof FlywayMigrateException migrateException) {
                sqlState = validatedSqlState(migrateException.getSqlState(), sqlState);
                MigrationInfo migration = migrateException.getMigration();
                if (migration != null) {
                    migrationErrorCode = safeFlywayErrorCode(
                            migrateException.getMigrationErrorCode(), migrationErrorCode);
                    if (migration.getVersion() != null) {
                        migrationVersion = validatedVersion(migration.getVersion().toString(), migrationVersion);
                    }
                }
                migrationLine = validatedLine(migrateException.getLineNumber(), migrationLine);
            }
            if (current instanceof FlywaySqlScriptException scriptException) {
                Resource resource = scriptException.getResource();
                if (resource != null) {
                    migrationVersion = versionFromFilename(resource.getFilename(), migrationVersion);
                }
                migrationLine = validatedLine(scriptException.getLineNumber(), migrationLine);
            }

            current = current.getCause();
        }

        if (classNames.isEmpty()) {
            classNames.add("UnknownException");
        }
        // Prefer the closest structured classification, while retaining each source
        // separately so a wrapper code cannot overwrite a more useful SQL/migration code.
        flywayErrorCode = firstAvailable(sqlErrorCode, migrationErrorCode, generalFlywayErrorCode);
        String rootCause = classNames.get(classNames.size() - 1);
        boolean truncated = current != null;
        return "exception_chain=" + String.join(">", classNames) + "\n"
                + "root_cause_class=" + rootCause + "\n"
                + "sqlstate=" + sqlState + "\n"
                + "flyway_error_code=" + flywayErrorCode + "\n"
                + "flyway_sql_error_code=" + sqlErrorCode + "\n"
                + "flyway_migration_error_code=" + migrationErrorCode + "\n"
                + "flyway_general_error_code=" + generalFlywayErrorCode + "\n"
                + "migration_version=" + migrationVersion + "\n"
                + "migration_line=" + (migrationLine > 0 ? migrationLine : "unavailable") + "\n"
                + "exception_chain_truncated=" + truncated + "\n"
                + "detail=Exception messages, SQL statements, stack traces, and connection details are suppressed.";
    }

    /** Adds an allow-listed runner phase to the sanitized exception report. */
    public static String format(Throwable failure, String phase) {
        String safePhase = phase != null && SAFE_PHASES.contains(phase) ? phase : "unavailable";
        return format(failure) + "\nmigration_phase=" + safePhase;
    }

    /** Exception-only summary for read-only preflight output. */
    public static String formatExceptionSummary(Throwable failure) {
        return format(failure).lines()
                .filter(line -> line.startsWith("exception_chain=")
                        || line.startsWith("root_cause_class=")
                        || line.startsWith("sqlstate=")
                        || line.startsWith("flyway_error_code="))
                .collect(Collectors.joining("\n"));
    }

    private static String safeFlywayErrorCode(Object candidate, String previous) {
        if (!(candidate instanceof Enum<?> errorCode)) {
            return previous;
        }
        String packageName = errorCode.getDeclaringClass().getPackageName();
        if ((packageName.startsWith("org.flywaydb.core") || packageName.startsWith("org.flywaydb.database"))
                && SAFE_ERROR_CODE.matcher(errorCode.name()).matches()) {
            return errorCode.name();
        }
        return previous;
    }

    private static String firstAvailable(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !"unavailable".equals(candidate)) {
                return candidate;
            }
        }
        return "unavailable";
    }

    private static String validatedSqlState(String candidate, String previous) {
        return candidate != null && SQL_STATE.matcher(candidate).matches() ? candidate : previous;
    }

    private static String validatedVersion(String candidate, String previous) {
        return candidate != null && FLYWAY_VERSION.matcher(candidate).matches() ? candidate : previous;
    }

    private static int validatedLine(int candidate, int previous) {
        return candidate > 0 ? candidate : previous;
    }

    private static String versionFromFilename(String candidate, String previous) {
        if (candidate == null) {
            return previous;
        }
        int separator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
        String filename = candidate.substring(separator + 1);
        Matcher matcher = VERSIONED_MIGRATION.matcher(filename);
        return matcher.matches() ? matcher.group(1).replace('_', '.') : previous;
    }

    private static String safeClassName(Class<?> type) {
        if (type == null) {
            return "UnknownException";
        }
        String name = type.getSimpleName().replaceAll("[^A-Za-z0-9_$]", "");
        return name.isEmpty() ? "UnknownException" : name;
    }
}
