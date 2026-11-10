package com.barnali.api.admin;

import com.barnali.api.auth.AppPrincipal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component("adminAuthorization")
public class AdminAuthorization {
    private final JdbcTemplate jdbc;

    public AdminAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public boolean has(Authentication authentication, String permission) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof AppPrincipal principal)) {
            return false;
        }
        // The transaction manager binds identity from this authenticated principal. The SQL
        // permission routine independently rechecks active account and role in PostgreSQL.
        return Boolean.TRUE.equals(jdbc.query("SELECT barnali.actor_has_permission(?)",
                rs -> rs.next() && rs.getBoolean(1), permission));
    }

    @Transactional(readOnly = true)
    public boolean isOwner(Authentication authentication) {
        // ADMIN_TEAM_MANAGE is granted only to OWNER and also enforces active-user
        // and completed-bootstrap-password checks in the shared permission query.
        return has(authentication, "ADMIN_TEAM_MANAGE");
    }

    public UUID userId(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppPrincipal principal) return principal.id();
        throw new IllegalStateException("Authenticated application user is required.");
    }
}
