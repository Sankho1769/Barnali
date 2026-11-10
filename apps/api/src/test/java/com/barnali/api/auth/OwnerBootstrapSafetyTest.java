package com.barnali.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;

class OwnerBootstrapSafetyTest {
    @Test
    void isolatedBootstrapRequiresExplicitApprovalAndProfileGating() {
        assertThat(OwnerBootstrapApplication.isApproved(null)).isFalse();
        assertThat(OwnerBootstrapApplication.isApproved("false")).isFalse();
        assertThat(OwnerBootstrapApplication.isApproved("true")).isTrue();
        assertThat(OwnerBootstrapApplication.class.getAnnotation(Profile.class).value())
                .containsExactly("barnali-owner-bootstrap-only");
        assertThat(OwnerBootstrapApplication.class.getAnnotation(ConditionalOnProperty.class).havingValue())
                .isEqualTo("true");
        assertThat(OwnerBootstrapApplication.safeArguments())
                .contains("--spring.profiles.active=barnali-owner-bootstrap-only",
                        "--barnali.owner.bootstrap.enabled=true", "--spring.flyway.enabled=false",
                        "--spring.main.web-application-type=none");
    }

    @Test
    void ownerRunnerUsesOnlyReadinessAndOneTimeProvisioningFunctions() throws Exception {
        String runner = Files.readString(Path.of("src/main/java/com/barnali/api/auth/OwnerBootstrapRunner.java"));
        assertThat(runner).contains("barnali.owner_bootstrap_readiness()", "barnali.provision_initial_owner(?,?)");
        assertThat(runner).doesNotContain("admin_bootstrap_guard", "INSERT INTO users", "INSERT INTO admin_accounts",
                "AdminAuditService", "SELECT count(*) FROM admin_accounts");
    }

    @Test
    void ownerConnectionUsesDedicatedProvisionerAndReadOnlyReadiness() throws Exception {
        String configuration = Files.readString(Path.of("src/main/java/com/barnali/operations/OwnerBootstrapConfiguration.java"));
        String readiness = Files.readString(Path.of("src/main/java/com/barnali/api/auth/OwnerBootstrapReadiness.java"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(configuration).contains("ownerProvisionerDataSource", "barnali.owner.provisioner.database-url",
                "barnali.owner.provisioner.database-username", "barnali.owner.provisioner.database-password");
        assertThat(application).contains("OWNER_PROVISIONER_DATABASE_URL", "OWNER_PROVISIONER_DATABASE_USERNAME",
                "OWNER_PROVISIONER_DATABASE_PASSWORD");
        assertThat(readiness).contains("setReadOnly(true)", "SET TRANSACTION READ ONLY",
                "barnali.owner_bootstrap_readiness()", "barnali_provisioner.");
        assertThat(readiness).doesNotContain("barnali.admin_bootstrap_guard", "barnali.admin_accounts",
                "barnali.users", "barnali.flyway_schema_history");
    }

    @Test
    void provisioningMigrationHasFixedSearchPathAtomicGuardAndRestrictedExecuteGrant() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V3__row_security_and_scoped_auth_operations.sql"));
        assertThat(migration).contains("ALTER TABLE admin_bootstrap_guard ADD COLUMN consumed_at TIMESTAMPTZ",
                "CREATE FUNCTION provision_initial_owner(", "SECURITY DEFINER",
                "SET search_path = pg_catalog, barnali, pg_temp",
                "UPDATE barnali.admin_bootstrap_guard SET consumed_at=pg_catalog.clock_timestamp()",
                "VALUES (target_id,'OWNER_BOOTSTRAP','admin_account',target_id::text,'SUCCESS')",
                "GRANT EXECUTE ON FUNCTION owner_bootstrap_readiness(),provision_initial_owner(TEXT,TEXT) TO barnali_provisioner");
        assertThat(migration).doesNotContain("FORCE ROW LEVEL SECURITY", "GRANT BYPASSRLS");
        String runtimeGrant = migration.substring(migration.indexOf("GRANT EXECUTE ON FUNCTION current_actor_id()"));
        runtimeGrant = runtimeGrant.substring(0, runtimeGrant.indexOf(" TO barnali_runtime;") + " TO barnali_runtime;".length());
        assertThat(runtimeGrant).doesNotContain("owner_bootstrap_readiness", "provision_initial_owner");
    }

    @Test
    void diagnosticsReportNestedTypesAndSqlStateWithoutMessages() {
        String secret = "synthetic-password-that-must-never-appear";
        RuntimeException failure = new IllegalStateException(secret,
                new SQLException("jdbc:postgresql://user:" + secret + "@host/db", "42501"));

        String report = OwnerBootstrapDiagnostics.format(failure);

        assertThat(report).contains("exception_chain=IllegalStateException>SQLException", "sqlstate=42501");
        assertThat(report).doesNotContain(secret, "jdbc:postgresql", "42501@");
    }

    @Test
    void sanitizedDatabaseExceptionRetainsOnlyValidatedSqlState() {
        String report = OwnerBootstrapDiagnostics.format(new OwnerBootstrapDiagnostics.SafeDatabaseException("08006"));
        assertThat(report).contains("SafeDatabaseException", "sqlstate=08006");
        assertThat(report).doesNotContain("database URL", "password");

        String invalidStateReport = OwnerBootstrapDiagnostics.format(
                new OwnerBootstrapDiagnostics.SafeDatabaseException("secret-value"));
        assertThat(invalidStateReport).contains("sqlstate=unavailable");
        assertThat(invalidStateReport).doesNotContain("secret-value");
    }
}
