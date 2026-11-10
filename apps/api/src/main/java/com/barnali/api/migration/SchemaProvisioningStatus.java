package com.barnali.api.migration;

/** Read-only assessment of the externally provisioned private-schema ownership path. */
final class SchemaProvisioningStatus {
    private SchemaProvisioningStatus() {}

    static String assess(boolean schemaExists, boolean migrationRoleOwnsSchema, boolean migrationRoleHasDatabaseCreate) {
        if (migrationRoleHasDatabaseCreate) return "BLOCKED_MIGRATOR_HAS_DATABASE_CREATE";
        if (!schemaExists) return "WAITING_AUTHORIZED_OPERATOR_CREATE_AND_ASSIGN_OWNER";
        if (!migrationRoleOwnsSchema) return "BLOCKED_MIGRATOR_IS_NOT_SCHEMA_OWNER";
        return "VERIFIED_MIGRATOR_OWNS_SCHEMA_WITHOUT_DATABASE_CREATE";
    }
}
