package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Offline checks that keep the optional real-PostgreSQL fixture tied to the actual migrations. */
class BarnaliPostgresFixtureContractTest {
    @Test
    void optInFixtureAppliesTheActualMigrationsWithSeparateSyntheticPrincipals() throws Exception {
        String fixture = Files.readString(Path.of(
                "src/test/java/com/barnali/api/security/BarnaliV3MigrationPostgresIntegrationTest.java"));
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(fixture).contains("Flyway.configure()", "classpath:db/migration", ".schemas(SCHEMA)",
                ".defaultSchema(SCHEMA)", ".createSchemas(false)", ".cleanDisabled(true)",
                "flyway.migrate().migrationsExecuted()).isEqualTo(3)", "flyway.validate()",
                "postgres:17-alpine", "withDatabaseName(\"barnali_v3_it\")",
                "CREATE SCHEMA barnali AUTHORIZATION \" + MIGRATOR",
                "NOSUPERUSER NOCREATEDB NOCREATEROLE", "NOBYPASSRLS", "BarnaliJdbcTransactionManager");
        assertThat(fixture).contains("runtimeRoleHasOnlyReviewedAcl", "publicContentIsVisible",
                "MembersCannotReadOtherUsersPrivateRows", "authenticationRegistrationRateLimitAndRecovery",
                "moderationAnalyticsAndAdminViews", "invitationsRequireOwner",
                "ownerProvisioningIsSeparateAtomic", "concurrentRecoveryCompletions",
                "identityUsesTransactionLocalSetting");
        assertThat(fixture).doesNotContain(".env", "supabase.com", "DATABASE_URL", "MIGRATION_DATABASE_URL",
                "flyway.clean(", "baselineOnMigrate", "OwnerBootstrapRunner", "SpringApplication.run");
        assertThat(pom).contains("<id>postgres-integration</id>",
                "**/*Postgres*IntegrationTest.java", "testcontainers-junit-jupiter", "testcontainers-postgresql");
    }
}
