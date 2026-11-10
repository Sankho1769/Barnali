package com.barnali.api.migration;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record MigrationTarget(String host, int port, String database, String sslMode) {
    public static final String EXPECTED_SCHEMA = "barnali";
    public static final String EXPECTED_HOST = "aws-0-ap-northeast-2.pooler.supabase.com";
    public static final int EXPECTED_PORT = 5432;
    public static final String EXPECTED_DATABASE = "postgres";
    private static final String JDBC_PREFIX = "jdbc:";

    public static MigrationTarget parse(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank() || !jdbcUrl.startsWith(JDBC_PREFIX)) {
            throw new IllegalArgumentException("A PostgreSQL JDBC URL is required.");
        }

        try {
            URI uri = URI.create(jdbcUrl.substring(JDBC_PREFIX.length()));
            if (!"postgresql".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getHost() == null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("The PostgreSQL JDBC URL contains unsupported connection details.");
            }

            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.length() < 2 || path.substring(1).contains("/")) {
                throw new IllegalArgumentException("The PostgreSQL JDBC URL database path is invalid.");
            }

            Map<String, String> query = parseQuery(uri.getRawQuery());
            if (query.containsKey("user") || query.containsKey("password")) {
                throw new IllegalArgumentException("Credentials must be supplied through environment variables, not the JDBC URL.");
            }
            String currentSchema = query.get("currentschema");
            if (currentSchema != null && !EXPECTED_SCHEMA.equalsIgnoreCase(currentSchema)) {
                throw new IllegalArgumentException("The JDBC URL selects a schema outside the approved target.");
            }
            if (query.containsKey("options")) {
                throw new IllegalArgumentException("JDBC URL startup options are not allowed for the migration command.");
            }

            String sslMode = query.get("sslmode");
            if (sslMode == null || !isTlsMode(sslMode)) {
                throw new IllegalArgumentException("The JDBC URL must require TLS with sslmode=require or a verifying mode.");
            }

            int port = uri.getPort();
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("The PostgreSQL JDBC URL must include a valid explicit port.");
            }
            return new MigrationTarget(uri.getHost().toLowerCase(Locale.ROOT), port,
                    URLDecoder.decode(path.substring(1), StandardCharsets.UTF_8), sslMode.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            // URI/decoder exception messages may contain the input URL. Replace them with a fixed, sanitized message.
            if (exception.getMessage() != null && exception.getMessage().startsWith("The ")) {
                throw exception;
            }
            throw new IllegalArgumentException("The PostgreSQL JDBC URL could not be parsed safely.");
        }
    }

    public void requireBarnaliTarget() {
        if (!EXPECTED_HOST.equals(host) || port != EXPECTED_PORT || !EXPECTED_DATABASE.equals(database)) {
            throw new IllegalStateException("The configured database target does not match the approved BARNALI Supabase Session Pooler.");
        }
    }

    String sanitizedSummary() {
        return "host=" + host + ",port=" + port + ",database=" + database
                + ",schema=" + EXPECTED_SCHEMA + ",sslmode=" + sslMode;
    }

    private static boolean isTlsMode(String sslMode) {
        return "require".equalsIgnoreCase(sslMode)
                || "verify-ca".equalsIgnoreCase(sslMode)
                || "verify-full".equalsIgnoreCase(sslMode);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String item : rawQuery.split("&")) {
            String[] pair = item.split("=", 2);
            try {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String value = pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                result.put(key, value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("The PostgreSQL JDBC URL query could not be parsed safely.");
            }
        }
        return result;
    }
}
