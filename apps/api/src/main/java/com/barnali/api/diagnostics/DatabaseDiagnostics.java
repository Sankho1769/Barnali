package com.barnali.api.diagnostics;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Safe parsing and redaction helpers for the standalone database diagnostic. */
public final class DatabaseDiagnostics {
    private static final Set<String> SECRET_QUERY_PARAMETERS = Set.of(
            "user", "username", "password", "passwd", "sslpassword", "sslkey",
            "token", "access_token", "secret", "api_key", "apikey");
    private static final Set<String> SUPPORTED_SSL_MODES = Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private DatabaseDiagnostics() {
    }

    public record JdbcTarget(String host, int port, String database, String sslMode,
            boolean credentialsEmbedded, boolean fragmentPresent) {
        public boolean requiresEncryptedConnection() {
            return Set.of("require", "verify-ca", "verify-full").contains(sslMode);
        }
    }

    public record SanitizedFailure(String exceptionClass, String sqlState, String causeClass) {
        public String format() {
            return "exception_class=" + exceptionClass + "; sqlstate=" + sqlState
                    + (causeClass.isEmpty() ? "" : "; cause_class=" + causeClass);
        }
    }

    public static JdbcTarget parseJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw invalidJdbcUrl();
        }

        final URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException ignored) {
            throw invalidJdbcUrl();
        }

        String host = uri.getHost();
        if (!"postgresql".equalsIgnoreCase(uri.getScheme()) || host == null
                || !host.matches("[A-Za-z0-9.-]{1,253}")) {
            throw invalidJdbcUrl();
        }

        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        if (port < 1 || port > 65535) {
            throw invalidJdbcUrl();
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        String database = path.startsWith("/") ? path.substring(1) : path;
        if (!database.matches("[A-Za-z0-9_.-]{0,63}")) {
            database = "invalid";
        }

        boolean embeddedCredentials = uri.getRawUserInfo() != null;
        String sslMode = "missing";
        boolean sslModeSeen = false;
        boolean duplicateSslMode = false;
        String query = uri.getRawQuery();
        if (query != null) {
            for (String parameter : query.split("&", -1)) {
                int separator = parameter.indexOf('=');
                String key = decodeQueryPart(separator < 0 ? parameter : parameter.substring(0, separator))
                        .toLowerCase(Locale.ROOT);
                if (SECRET_QUERY_PARAMETERS.contains(key)) {
                    embeddedCredentials = true;
                }
                if ("sslmode".equals(key)) {
                    if (sslModeSeen) {
                        duplicateSslMode = true;
                        continue;
                    }
                    sslModeSeen = true;
                    String value = separator < 0 ? "" : decodeQueryPart(parameter.substring(separator + 1));
                    String normalized = value.toLowerCase(Locale.ROOT);
                    sslMode = SUPPORTED_SSL_MODES.contains(normalized) ? normalized : "invalid";
                }
            }
        }
        if (duplicateSslMode) {
            sslMode = "invalid";
        }

        return new JdbcTarget(host, port, database, sslMode, embeddedCredentials, uri.getRawFragment() != null);
    }

    public static boolean hasSessionPoolerHostShape(String host) {
        return host != null && host.matches("aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com");
    }

    public static boolean hasSessionPoolerUsernameShape(String username) {
        return username != null && username.matches("[^\\s.@]+\\.[a-z0-9]{10,}");
    }

    public static boolean hasMinimumHmacLength(String hmacKey) {
        return hmacKey != null && hmacKey.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public static boolean isPresent(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value != null && !value.isBlank();
    }

    public static boolean hasCredentialSourceConflict(JdbcTarget target, Map<String, String> environment) {
        return target.credentialsEmbedded()
                && isPresent(environment, "DATABASE_USERNAME")
                && isPresent(environment, "DATABASE_PASSWORD");
    }

    /** Does not inspect or include exception messages, which may contain connection details. */
    public static SanitizedFailure sanitizeFailure(Throwable failure) {
        String exceptionClass = safeClassName(failure == null ? null : failure.getClass());
        String causeClass = "";
        String sqlState = "unknown";
        Throwable current = failure;
        boolean first = true;
        while (current != null) {
            if (!first && causeClass.isEmpty()) {
                causeClass = safeClassName(current.getClass());
            }
            if (current instanceof SQLException sqlException && "unknown".equals(sqlState)) {
                String candidate = sqlException.getSQLState();
                if (candidate != null && candidate.matches("[A-Za-z0-9]{5}")) {
                    sqlState = candidate;
                }
            }
            first = false;
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return new SanitizedFailure(exceptionClass, sqlState, causeClass);
    }

    private static IllegalArgumentException invalidJdbcUrl() {
        return new IllegalArgumentException("DATABASE_URL is not a valid PostgreSQL JDBC URL.");
    }

    private static String decodeQueryPart(String part) {
        try {
            return URLDecoder.decode(part, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private static String safeClassName(Class<?> type) {
        if (type == null) {
            return "UnknownException";
        }
        String name = type.getSimpleName().replaceAll("[^A-Za-z0-9_$]", "");
        return name.isEmpty() ? "UnknownException" : name;
    }
}
