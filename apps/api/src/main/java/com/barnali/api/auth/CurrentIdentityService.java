package com.barnali.api.auth;

import com.barnali.api.admin.AdminRole;
import com.barnali.api.admin.PermissionMatrix;
import com.barnali.api.config.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentIdentityService {
    private final JdbcTemplate jdbc;

    public CurrentIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SessionUser current(AppPrincipal principal) {
        return jdbc.query("SELECT * FROM barnali.current_session_user()", rs -> {
            if (!rs.next()) throw ApiException.unauthorized("Your session is no longer active. Sign in again.");
            UUID resolvedId = rs.getObject("id", UUID.class);
            if (!resolvedId.equals(principal.id())) throw ApiException.unauthorized("Your session identity is no longer valid.");
            String role = rs.getString("admin_role");
            List<String> permissions = role == null ? List.of() : PermissionMatrix.permissions(AdminRole.valueOf(role));
            return new SessionUser(resolvedId, rs.getString("username"),
                    rs.getString("display_name"), rs.getString("email"), role == null ? "MEMBER" : role,
                    permissions, rs.getBoolean("must_change_password"));
        });
    }

    @Transactional(readOnly = true)
    public boolean isActive(AppPrincipal principal) {
        return jdbc.query("SELECT status = 'ACTIVE' AND auth_version = ? FROM users WHERE id = barnali.current_actor_id()",
                rs -> rs.next() && rs.getBoolean(1), principal.authVersion());
    }
}
