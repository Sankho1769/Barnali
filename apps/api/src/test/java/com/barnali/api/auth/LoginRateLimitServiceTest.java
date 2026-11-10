package com.barnali.api.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.barnali.api.config.BarnaliProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LoginRateLimitServiceTest {
    @Test
    void requiresAtLeastThirtyTwoUtf8BytesForHmacKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThrows(IllegalStateException.class,
                () -> new LoginRateLimitService(jdbc, properties("")));
        assertThrows(IllegalStateException.class,
                () -> new LoginRateLimitService(jdbc, properties("x".repeat(31))));
        assertDoesNotThrow(
                () -> new LoginRateLimitService(jdbc, properties("x".repeat(32))));
    }

    private static BarnaliProperties properties(String hmacKey) {
        return new BarnaliProperties("dev", "http://localhost:3000", "http://localhost:8080",
                "owner@example.invalid", "", hmacKey, "", "", "");
    }
}
