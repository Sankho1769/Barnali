package com.barnali.api.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchemaProvisioningStatusTest {
    @Test
    void instructsAuthorizedOperatorProvisioningWhenSchemaIsAbsentAndMigratorHasNoDatabaseCreate() {
        assertThat(SchemaProvisioningStatus.assess(false, false, false))
                .isEqualTo("WAITING_AUTHORIZED_OPERATOR_CREATE_AND_ASSIGN_OWNER");
    }

    @Test
    void blocksBroadDatabaseCreateEvenWhenSchemaIsAbsent() {
        assertThat(SchemaProvisioningStatus.assess(false, false, true))
                .isEqualTo("BLOCKED_MIGRATOR_HAS_DATABASE_CREATE");
    }

    @Test
    void requiresMigratorOwnershipAfterProvisioningWithoutDatabaseCreate() {
        assertThat(SchemaProvisioningStatus.assess(true, false, false))
                .isEqualTo("BLOCKED_MIGRATOR_IS_NOT_SCHEMA_OWNER");
        assertThat(SchemaProvisioningStatus.assess(true, true, false))
                .isEqualTo("VERIFIED_MIGRATOR_OWNS_SCHEMA_WITHOUT_DATABASE_CREATE");
    }
}
