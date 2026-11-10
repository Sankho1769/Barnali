package com.barnali.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private final JdbcTemplate jdbc;

    public AppUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public AppPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
        return jdbc.query("SELECT * FROM barnali.auth_principal_by_email(?)", (rs, row) -> new AppPrincipal(
                rs.getObject("id", java.util.UUID.class), rs.getString("email"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getString("status"),
                rs.getBoolean("must_change_password"), rs.getLong("auth_version")), email.toLowerCase())
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    }
}
