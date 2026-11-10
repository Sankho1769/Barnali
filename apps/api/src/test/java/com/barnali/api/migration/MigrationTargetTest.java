package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MigrationTargetTest {
    private static final String VALID_URL = "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require";

    @Test
    void parsesAndSummarizesOnlyNonSecretTargetDetails() {
        MigrationTarget target = MigrationTarget.parse(VALID_URL);
        target.requireBarnaliTarget();

        assertThat(target.sanitizedSummary())
                .isEqualTo("host=aws-0-ap-northeast-2.pooler.supabase.com,port=5432,database=postgres,schema=barnali,sslmode=require");
    }

    @Test
    void refusesCredentialsEmbeddedInUrlWithoutEchoingThem() {
        String secret = "do-not-print-this-password";
        String url = "jdbc:postgresql://user:" + secret
                + "@aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require";

        assertThatThrownBy(() -> MigrationTarget.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    void refusesCredentialQueryParametersWithoutEchoingThem() {
        String secret = "do-not-print-this-password";
        String url = VALID_URL + "&password=" + secret;

        assertThatThrownBy(() -> MigrationTarget.parse(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    void requiresExactPoolerHostPortDatabaseAndTls() {
        assertThatThrownBy(() -> MigrationTarget.parse(
                "jdbc:postgresql://other.pooler.supabase.com:5432/postgres?sslmode=require").requireBarnaliTarget())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MigrationTarget.parse(
                "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com/postgres?sslmode=require"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MigrationTarget.parse(
                "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/other?sslmode=require").requireBarnaliTarget())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MigrationTarget.parse(
                "jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=prefer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBarnaliSchemaOverrideAndRefusesOtherSchemaOverrides() {
        assertThat(MigrationTarget.parse(VALID_URL + "&currentSchema=barnali").sanitizedSummary())
                .contains("schema=barnali");
        assertThatThrownBy(() -> MigrationTarget.parse(VALID_URL + "&currentSchema=public"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("public");
        assertThatThrownBy(() -> MigrationTarget.parse(VALID_URL + "&currentSchema=private_data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("private_data");
    }
}
