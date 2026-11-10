package com.barnali.api.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.barnali.api.config.BarnaliProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class OwnerBootstrapRunnerTest {
    private static final String READINESS_SQL = "SELECT barnali.owner_bootstrap_readiness()";
    private static final String PROVISION_SQL = "SELECT barnali.provision_initial_owner(?,?)";

    @Test
    void missingBootstrapPasswordFailsBeforeCallingDatabaseOrEncoder() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);

        assertThatThrownBy(() -> runner(jdbc, passwords, properties("owner@example.test", "")).run(null))
                .isInstanceOf(IllegalStateException.class);

        verifyNoMoreInteractions(jdbc, passwords);
    }

    @Test
    void consumedOrInconsistentOwnerStateFailsClosedWithoutHashingOrProvisioning() {
        for (String state : new String[] {"CONSUMED", "INCONSISTENT"}) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            PasswordEncoder passwords = mock(PasswordEncoder.class);
            when(jdbc.queryForObject(READINESS_SQL, String.class)).thenReturn(state);

            assertThatThrownBy(() -> runner(jdbc, passwords,
                    properties("owner@example.test", "ValidSyntheticPassword9!")).run(null))
                    .isInstanceOf(IllegalStateException.class);

            verify(passwords, never()).encode(anyString());
            verify(jdbc).queryForObject(READINESS_SQL, String.class);
            verifyNoMoreInteractions(passwords, jdbc);
        }
    }

    @Test
    void readyStateCallsOnlyTheOneTimeFunctionWithHashedPassword() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        String bootstrapPassword = "ValidSyntheticPassword9!";
        String encoded = "synthetic-bcrypt-hash";
        when(jdbc.queryForObject(READINESS_SQL, String.class)).thenReturn("READY");
        when(passwords.encode(bootstrapPassword)).thenReturn(encoded);
        when(jdbc.queryForObject(eq(PROVISION_SQL), eq(Boolean.class), eq("owner@example.test"), eq(encoded)))
                .thenReturn(true);

        runner(jdbc, passwords, properties("owner@example.test", bootstrapPassword)).run(null);

        verify(jdbc).queryForObject(READINESS_SQL, String.class);
        verify(jdbc).queryForObject(PROVISION_SQL, Boolean.class, "owner@example.test", encoded);
        verify(passwords).encode(bootstrapPassword);
        verifyNoMoreInteractions(jdbc, passwords);
    }

    private static OwnerBootstrapRunner runner(JdbcTemplate jdbc, PasswordEncoder passwords, BarnaliProperties properties) {
        return new OwnerBootstrapRunner(jdbc, passwords, properties);
    }

    private static BarnaliProperties properties(String ownerEmail, String bootstrapPassword) {
        return new BarnaliProperties("dev", "http://localhost:3000", "http://localhost:8080",
                ownerEmail, bootstrapPassword, "x".repeat(32), "", "", "");
    }
}
