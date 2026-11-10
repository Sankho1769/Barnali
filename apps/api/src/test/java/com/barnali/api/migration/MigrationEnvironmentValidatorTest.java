package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MigrationEnvironmentValidatorTest {
    private static final String URL = "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require";

    @Test
    void acceptsMatchingRuntimeAndFlywayTargetsWithoutExposingCredentials() {
        MockEnvironment environment = configuredEnvironment();

        ValidatedMigrationTarget target = MigrationEnvironmentValidator.validate(environment);

        assertThat(target.sanitizedSummary()).contains("database=postgres", "schema=barnali", "sslmode=require");
        assertThat(target.sanitizedSummary()).doesNotContain("not-a-real-secret");
    }

    @Test
    void rejectsASeparateFlywayTargetWithoutEchoingEitherUrl() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.flyway.url", "jdbc:postgresql://different.example:5432/postgres?sslmode=require");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("different.example");
    }

    @Test
    void requiresCredentialsFromSeparateProperties() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.flyway.password", "");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not-a-real-secret");
    }

    @Test
    void rejectsMigrationFallbackToTheRuntimePrincipal() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.flyway.user", "postgres.projectref1234567890");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("separate database principals")
                .hasMessageNotContaining("projectref1234567890");
    }

    @Test
    void rejectsRuntimeAndMigrationCredentialsScopedToDifferentProjects() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.flyway.user", "barnali_migrator.otherproject123456");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same Supabase project")
                .hasMessageNotContaining("otherproject123456");
    }

    @Test
    void rejectsAProjectReferenceThatDoesNotMatchTheOperatorSuppliedExpectedValue() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("barnali.expected-supabase-project-ref", "differentref123456");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator-supplied Supabase project reference")
                .hasMessageNotContaining("projectref1234567890")
                .hasMessageNotContaining("differentref123456");
    }

    @Test
    void requiresAnIndependentExpectedProjectReference() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("barnali.expected-supabase-project-ref", "");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator-supplied Supabase project reference")
                .hasMessageNotContaining("projectref1234567890");
    }

    @Test
    void rejectsRuntimeSearchPathOutsideThePrivateSchema() {
        MockEnvironment environment = configuredEnvironment()
                .withProperty("spring.datasource.hikari.schema", "public");

        assertThatThrownBy(() -> MigrationEnvironmentValidator.validate(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("public");
    }

    private static MockEnvironment configuredEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", URL)
                .withProperty("spring.datasource.username", "postgres.projectref1234567890")
                .withProperty("spring.datasource.password", "not-a-real-secret")
                .withProperty("spring.datasource.hikari.schema", "barnali")
                .withProperty("spring.flyway.url", URL)
                .withProperty("spring.flyway.user", "barnali_migrator.projectref1234567890")
                .withProperty("spring.flyway.password", "different-synthetic-secret")
                .withProperty("barnali.expected-supabase-project-ref", "projectref1234567890");
    }
}
