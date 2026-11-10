package com.barnali.api.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatabaseDiagnosticsTest {
    @Test
    void parsesSessionPoolerUrlWithoutRetainingCredentials() {
        String jdbcUrl = "jdbc:postgresql://aws-1-us-east-1.pooler.supabase.com:5432/postgres?sslmode=require";

        DatabaseDiagnostics.JdbcTarget target = DatabaseDiagnostics.parseJdbcUrl(jdbcUrl);

        assertEquals("aws-1-us-east-1.pooler.supabase.com", target.host());
        assertEquals(5432, target.port());
        assertEquals("postgres", target.database());
        assertEquals("require", target.sslMode());
        assertFalse(target.credentialsEmbedded());
        assertFalse(target.fragmentPresent());
    }

    @Test
    void defaultsPostgresPortAndDetectsCredentialsInAuthorityAndQuery() {
        DatabaseDiagnostics.JdbcTarget authority = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://private-user:private-pass@db.example.test/community");
        DatabaseDiagnostics.JdbcTarget query = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://db.example.test/community?sslmode=require&password=private-pass");
        DatabaseDiagnostics.JdbcTarget fragment = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://db.example.test/community#private-fragment");

        assertEquals(5432, authority.port());
        assertTrue(authority.credentialsEmbedded());
        assertTrue(query.credentialsEmbedded());
        assertFalse(fragment.credentialsEmbedded());
        assertTrue(fragment.fragmentPresent());
    }

    @Test
    void identifiesUrlAndSeparateCredentialConflictWithoutExposingValues() {
        DatabaseDiagnostics.JdbcTarget target = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://db.example.test/postgres?user=private-user&password=private-pass");

        assertTrue(DatabaseDiagnostics.hasCredentialSourceConflict(target,
                Map.of("DATABASE_USERNAME", "private-user", "DATABASE_PASSWORD", "private-pass")));
        assertFalse(DatabaseDiagnostics.hasCredentialSourceConflict(
                DatabaseDiagnostics.parseJdbcUrl("jdbc:postgresql://db.example.test/postgres"), Map.of()));
    }

    @Test
    void rejectsAmbiguousOrInsecureSslModes() {
        DatabaseDiagnostics.JdbcTarget duplicate = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://db.example.test/postgres?sslmode=require&sslmode=disable");
        DatabaseDiagnostics.JdbcTarget missing = DatabaseDiagnostics.parseJdbcUrl(
                "jdbc:postgresql://db.example.test/postgres");

        assertEquals("invalid", duplicate.sslMode());
        assertFalse(duplicate.requiresEncryptedConnection());
        assertEquals("missing", missing.sslMode());
    }

    @Test
    void doesNotEchoMalformedUrlOrExceptionMessages() {
        String secret = "private-password-value";
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                () -> DatabaseDiagnostics.parseJdbcUrl("jdbc:postgresql://user:" + secret + "@bad host/db"));
        SQLException failure = new SQLException("URL includes " + secret + " and HMAC=private-hmac-value", "08001");

        String sanitized = DatabaseDiagnostics.sanitizeFailure(failure).format();
        assertFalse(malformed.getMessage().contains(secret));
        assertFalse(sanitized.contains(secret));
        assertFalse(sanitized.contains("private-hmac-value"));
        assertEquals("08001", DatabaseDiagnostics.sanitizeFailure(failure).sqlState());
    }

    @Test
    void validatesSessionPoolerUsernameShapeAndHmacLengthWithoutReturningValues() {
        assertTrue(DatabaseDiagnostics.hasSessionPoolerUsernameShape("postgres.abcdefghijklmnopqrst"));
        assertFalse(DatabaseDiagnostics.hasSessionPoolerUsernameShape("postgres"));
        assertTrue(DatabaseDiagnostics.hasMinimumHmacLength("0123456789abcdef0123456789abcdef"));
        assertFalse(DatabaseDiagnostics.hasMinimumHmacLength("short"));
        assertTrue(DatabaseDiagnostics.isPresent(Map.of("DATABASE_PASSWORD", "x"), "DATABASE_PASSWORD"));
    }
}
