package com.barnali.api.admin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PermissionMatrixTest {
    @Test
    void delegatedRolesHaveLeastPrivilege() {
        assertTrue(PermissionMatrix.allows(AdminRole.OWNER,"ADMIN_TEAM_MANAGE"));
        assertFalse(PermissionMatrix.allows(AdminRole.SUPER_ADMIN,"ADMIN_TEAM_MANAGE"));
        assertFalse(PermissionMatrix.allows(AdminRole.MODERATOR,"ADMIN_TEAM_MANAGE"));
        assertTrue(PermissionMatrix.allows(AdminRole.MODERATOR,"REPORT_RESOLVE"));
        assertFalse(PermissionMatrix.allows(AdminRole.MODERATOR,"ANALYTICS_READ"));
        assertTrue(PermissionMatrix.allows(AdminRole.ANALYTICS_VIEWER,"ANALYTICS_READ"));
        assertFalse(PermissionMatrix.allows(AdminRole.ANALYTICS_VIEWER,"USER_READ"));
        assertFalse(PermissionMatrix.allows(AdminRole.SUPPORT_ADMIN,"USER_SUSPEND"));
        assertFalse(PermissionMatrix.allows(AdminRole.SUPPORT_ADMIN,"ADMIN_TEAM_MANAGE"));
    }

    @Test
    void publicPermissionLookupRejectsUnknownRoles() {
        assertTrue(PermissionMatrix.publicPermissions("NOT_A_ROLE").isEmpty());
        assertTrue(PermissionMatrix.permissions(AdminRole.OWNER).contains("AUDIT_READ"));
    }
}
