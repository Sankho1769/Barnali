package com.barnali.api.auth;

import com.barnali.api.config.BarnaliProperties;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class OwnerBootstrapRunner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final BarnaliProperties properties;

    public OwnerBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder passwords, BarnaliProperties properties) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = properties.ownerEmail() == null ? "" : properties.ownerEmail().trim().toLowerCase(Locale.ROOT);
        String password = properties.ownerBootstrapPassword() == null ? "" : properties.ownerBootstrapPassword();
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("No owner exists. Set OWNER_EMAIL and OWNER_BOOTSTRAP_PASSWORD in the deployment environment for one-time bootstrap.");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") || email.length() > 254) {
            throw new IllegalStateException("OWNER_EMAIL must be a valid email address.");
        }
        if (!PasswordPolicy.isValid(password)) {
            throw new IllegalStateException("OWNER_BOOTSTRAP_PASSWORD does not meet the BARNALI password policy.");
        }
        String readiness = jdbc.queryForObject("SELECT barnali.owner_bootstrap_readiness()", String.class);
        if (!"READY".equals(readiness)) {
            throw new IllegalStateException("Owner provisioning is already consumed or the owner state is inconsistent; use the controlled recovery procedure.");
        }
        jdbc.queryForObject("SELECT barnali.provision_initial_owner(?,?)", Boolean.class,
                email, passwords.encode(password));
    }
}
