package com.barnali.api.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.barnali.api.auth.AppPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AdminAuthorizationTest {
    @Test
    void ownerGateUsesActiveAndPasswordChangePermissionPredicate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        AppPrincipal principal = new AppPrincipal(userId, "owner@example.invalid", "owner", "Owner",
                "unused", "ACTIVE", true, 0);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
                org.mockito.ArgumentMatchers.eq("ADMIN_TEAM_MANAGE"))).thenReturn(false);

        AdminAuthorization authorization = new AdminAuthorization(jdbc);

        assertFalse(authorization.isOwner(authentication));
        verify(jdbc).query(argThat(sql -> sql.contains("barnali.actor_has_permission(?)")),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
                org.mockito.ArgumentMatchers.eq("ADMIN_TEAM_MANAGE"));
    }
}
