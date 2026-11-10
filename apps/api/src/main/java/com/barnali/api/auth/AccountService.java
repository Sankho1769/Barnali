package com.barnali.api.auth;

import com.barnali.api.config.ApiException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final CurrentIdentityService identity;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords, CurrentIdentityService identity) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.identity = identity;
    }

    @Transactional
    public void register(String displayName, String username, String email, String password) {
        validatePassword(password);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        jdbc.queryForObject("SELECT barnali.register_user(?,?,?,?)", Boolean.class,
                displayName.trim(), normalizedUsername, normalizedEmail, passwords.encode(password));
    }

    @Transactional
    public void recordLogin() {
        jdbc.queryForObject("SELECT barnali.record_successful_login()", Boolean.class);
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword, String requestId) {
        validatePassword(newPassword);
        String currentHash = jdbc.query("SELECT barnali.current_password_hash()",
                rs -> rs.next() ? rs.getString(1) : null);
        if (currentHash == null || !passwords.matches(currentPassword, currentHash)) {
            throw ApiException.unauthorized("The current password is incorrect.");
        }
        if (passwords.matches(newPassword, currentHash)) {
            throw ApiException.badRequest("PASSWORD_REUSE", "Choose a password you have not used for this account.");
        }
        jdbc.queryForObject("SELECT barnali.change_current_password(?,?)", Boolean.class,
                passwords.encode(newPassword), requestId);
    }

    @Transactional
    public boolean resetPasswordWithToken(String tokenHash, String newPassword, String requestId) {
        validatePassword(newPassword);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT barnali.complete_password_reset(?,?,?)", Boolean.class,
                tokenHash, passwords.encode(newPassword), requestId));
    }

    @Transactional(readOnly = true)
    public SessionUser sessionUser(AppPrincipal principal) {
        return identity.current(principal);
    }

    public static void validatePassword(String password) {
        PasswordPolicy.validate(password);
    }
}
