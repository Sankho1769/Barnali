package com.barnali.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barnali.api.config.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class AdminServiceAuthorizationTest {
    @Test
    void userModerationRejectsAnInactiveDelegatedAdministratorBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        UUID actor = UUID.randomUUID();
        UUID inactiveAdmin = UUID.randomUUID();
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(), eq(inactiveAdmin)))
                .thenReturn("MODERATOR");

        AdminService service = new AdminService(jdbc, audit);
        ApiException failure = assertThrows(ApiException.class,
                () -> service.setUserStatus(actor, inactiveAdmin, "SUSPENDED", "reason", "request"));

        assertEquals("ADMIN_ACCOUNT_PROTECTED", failure.code());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }
}
