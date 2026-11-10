package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FunctionDefinitionSummaryTest {
    @Test
    void detectsRequestedEffectsWithoutRetainingOrExposingDefinition() {
        String definition = "CREATE FUNCTION private_secret_marker() RETURNS event_trigger AS $$ "
                + "BEGIN FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands() "
                + "WHERE cmd.command_tag = 'CREATE TABLE' LOOP IF cmd.schema_name = 'public' THEN "
                + "EXECUTE 'ALTER TABLE users ENABLE ROW LEVEL SECURITY'; "
                + "EXECUTE 'ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO anon'; "
                + "END IF; END LOOP; END $$ LANGUAGE plpgsql";

        FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(definition);

        assertThat(summary.tableCreation()).isEqualTo("NOT_DETECTED");
        assertThat(summary.tableAlteration()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.rowLevelSecurity()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.defaultPrivileges()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.ddlCommandInspectionDetected()).isTrue();
        assertThat(summary.createTableEventTagDetected()).isTrue();
        assertThat(summary.schemaNameReferenceDetected()).isTrue();
        assertThat(summary.schemaScope()).isEqualTo("PUBLIC_ONLY_FILTER_DETECTED");
        assertThat(summary.appliesToSchema("public")).isTrue();
        assertThat(summary.appliesToSchema("barnali")).isFalse();
        assertThat(summary.publicSchemaFilterDetected()).isTrue();
        assertThat(summary.dynamicSqlDetected()).isTrue();
        assertThat(summary.toString()).doesNotContain("private_secret_marker", "ALTER TABLE", "anon");
    }

    @Test
    void reportsNoMatchingOperationsWhenDefinitionHasNoDynamicSql() {
        FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(
                "CREATE FUNCTION harmless() RETURNS void LANGUAGE plpgsql AS $$ BEGIN RETURN; END $$");

        assertThat(summary.tableCreation()).isEqualTo("NOT_DETECTED");
        assertThat(summary.tableAlteration()).isEqualTo("NOT_DETECTED");
        assertThat(summary.rowLevelSecurity()).isEqualTo("NOT_DETECTED");
        assertThat(summary.defaultPrivileges()).isEqualTo("NOT_DETECTED");
        assertThat(summary.publicSchemaFilterDetected()).isFalse();
        assertThat(summary.schemaScope()).isEqualTo("UNDETERMINED");
        assertThat(summary.dynamicSqlDetected()).isFalse();
    }

    @Test
    void identifiesUnfilteredCreateTableEventScopeWithoutExposingFunctionSource() {
        String definition = "CREATE FUNCTION private_secret_marker() RETURNS event_trigger AS $$ "
                + "BEGIN FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands() "
                + "WHERE cmd.command_tag = 'CREATE TABLE' LOOP "
                + "EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', cmd.object_identity); "
                + "END LOOP; END $$ LANGUAGE plpgsql";

        FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(definition);

        assertThat(summary.schemaScope())
                .isEqualTo("ALL_CREATE_TABLE_EVENT_SCHEMAS_NO_SCHEMA_NAME_FILTER_DETECTED");
        assertThat(summary.tableAlteration()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.rowLevelSecurity()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.toString()).doesNotContain("private_secret_marker", "object_identity");
    }

    @Test
    void identifiesEveryNonNullSchemaReturnedByCreateTableEventInspection() {
        String definition = "CREATE FUNCTION private_secret_marker() RETURNS event_trigger AS $$ "
                + "BEGIN FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands() "
                + "WHERE cmd.command_tag = 'CREATE TABLE' LOOP "
                + "IF cmd.schema_name IS NOT NULL THEN EXECUTE 'ALTER TABLE x ENABLE ROW LEVEL SECURITY'; "
                + "END IF; END LOOP; END $$ LANGUAGE plpgsql";

        FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(definition);

        assertThat(summary.schemaScope()).isEqualTo("ALL_NON_NULL_SCHEMA_NAME_EVENT_SCHEMAS");
        assertThat(summary.appliesToSchema("barnali")).isTrue();
        assertThat(summary.rowLevelSecurity()).isEqualTo("DETECTED_DYNAMIC_SQL");
        assertThat(summary.toString()).doesNotContain("private_secret_marker", "ALTER TABLE x");
    }

    @Test
    void extractsOnlySafeSchemaIdentifiersFromAllowAndDenyLists() {
        FunctionDefinitionSummary summary = FunctionDefinitionSummary.analyze(
                "CREATE FUNCTION x() RETURNS event_trigger AS $$ BEGIN "
                        + "FOR cmd IN SELECT * FROM pg_event_trigger_ddl_commands() "
                        + "WHERE command_tag='CREATE TABLE' AND schema_name NOT IN ('pg_catalog','public') "
                        + "LOOP EXECUTE 'ALTER TABLE x ENABLE ROW LEVEL SECURITY'; END LOOP; END $$");

        assertThat(summary.schemaScope()).isEqualTo("ALL_EXCEPT_SCHEMAS:pg_catalog|public");
        assertThat(summary.appliesToSchema("barnali")).isTrue();
        assertThat(summary.appliesToSchema("public")).isFalse();
        assertThat(summary.toString()).doesNotContain("CREATE TABLE", "ALTER TABLE x");
    }
}
