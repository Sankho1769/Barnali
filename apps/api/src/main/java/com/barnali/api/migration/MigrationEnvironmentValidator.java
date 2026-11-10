package com.barnali.api.migration;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

record ValidatedMigrationTarget(MigrationTarget runtime, MigrationTarget migration) {
    String sanitizedSummary() {
        return migration.sanitizedSummary();
    }
}

final class MigrationEnvironmentValidator {
    private static final Pattern POOLER_USERNAME = Pattern.compile("^[a-z][a-z0-9_-]{0,62}\\.[a-z0-9]{10,64}$");
    private static final Pattern PROJECT_SCOPED_USERNAME = Pattern.compile("^[a-z][a-z0-9_-]{0,62}\\.([a-z0-9]{10,64})$");

    private MigrationEnvironmentValidator() {}

    static ValidatedMigrationTarget validate(Environment environment) {
        String runtimeUrl = required(environment.getProperty("spring.datasource.url"), "runtime database URL");
        String migrationUrl = required(environment.getProperty("spring.flyway.url"), "Flyway database URL");
        String runtimeUsername = required(environment.getProperty("spring.datasource.username"), "runtime database username");
        String migrationUsername = required(environment.getProperty("spring.flyway.user"), "Flyway database username");
        String expectedProjectRef = required(
                environment.getProperty("barnali.expected-supabase-project-ref"),
                "operator-supplied Supabase project reference");
        String runtimeSchema = required(environment.getProperty("spring.datasource.hikari.schema"), "runtime schema");
        required(environment.getProperty("spring.datasource.password"), "runtime database password");
        required(environment.getProperty("spring.flyway.password"), "Flyway database password");

        if (runtimeUsername.equals(migrationUsername)) {
            throw new IllegalStateException("Runtime and migration credentials must use separate database principals.");
        }

        if (!MigrationTarget.EXPECTED_SCHEMA.equals(runtimeSchema)) {
            throw new IllegalStateException("The runtime datasource must select the private BARNALI schema.");
        }

        if (!POOLER_USERNAME.matcher(runtimeUsername).matches()
                || !POOLER_USERNAME.matcher(migrationUsername).matches()) {
            throw new IllegalStateException("Database usernames must use the project-scoped Supabase Session Pooler format.");
        }
        if (!projectReference(runtimeUsername).equals(projectReference(migrationUsername))) {
            throw new IllegalStateException("Runtime and migration credentials must identify the same Supabase project.");
        }
        SupabaseProjectIdentity.requireUsernameMatches(runtimeUsername, expectedProjectRef);
        SupabaseProjectIdentity.requireUsernameMatches(migrationUsername, expectedProjectRef);

        MigrationTarget runtime = MigrationTarget.parse(runtimeUrl);
        MigrationTarget migration = MigrationTarget.parse(migrationUrl);
        runtime.requireBarnaliTarget();
        migration.requireBarnaliTarget();
        if (!Objects.equals(runtime, migration)) {
            throw new IllegalStateException("The application and Flyway datasource targets do not match.");
        }

        String hikariUrl = environment.getProperty("spring.datasource.hikari.jdbc-url");
        if (hikariUrl != null && !hikariUrl.isBlank()
                && !runtime.equals(MigrationTarget.parse(hikariUrl))) {
            throw new IllegalStateException("The Hikari datasource target does not match the approved BARNALI target.");
        }

        return new ValidatedMigrationTarget(runtime, migration);
    }

    private static String required(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required " + settingName + " is missing.");
        }
        return value;
    }

    private static String projectReference(String username) {
        var matcher = PROJECT_SCOPED_USERNAME.matcher(username);
        if (!matcher.matches()) {
            throw new IllegalStateException("A database username is not project-scoped.");
        }
        return matcher.group(1);
    }
}
