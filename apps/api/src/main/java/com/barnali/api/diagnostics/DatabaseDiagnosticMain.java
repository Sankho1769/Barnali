package com.barnali.api.diagnostics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/** Standalone entry point; intentionally does not initialize Spring or Flyway. */
public final class DatabaseDiagnosticMain {
    private DatabaseDiagnosticMain() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = System.getenv();
        for (String name : new String[] {"DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD",
                "APP_SECURITY_HMAC_KEY", "OWNER_EMAIL", "OWNER_BOOTSTRAP_PASSWORD"}) {
            System.out.println("env." + name + "=" + (DatabaseDiagnostics.isPresent(environment, name) ? "present" : "absent"));
        }
        System.out.println("spring_datasource_mapping=DATABASE_URL,DATABASE_USERNAME,DATABASE_PASSWORD");
        System.out.println("hmac_minimum_32_utf8_bytes="
                + DatabaseDiagnostics.hasMinimumHmacLength(environment.get("APP_SECURITY_HMAC_KEY")));

        final DatabaseDiagnostics.JdbcTarget target;
        try {
            target = DatabaseDiagnostics.parseJdbcUrl(environment.get("DATABASE_URL"));
        } catch (IllegalArgumentException ignored) {
            System.out.println("jdbc_url_parse=FAIL; reason=invalid_postgresql_jdbc_url");
            if (hasArgument(args, "--connect")) {
                System.out.println("postgres_connection=SKIPPED; reason=invalid_database_url");
            }
            return;
        }

        System.out.println("jdbc_url_parse=PASS");
        System.out.println("jdbc_host=" + target.host());
        System.out.println("jdbc_port=" + target.port());
        System.out.println("jdbc_database=" + target.database());
        System.out.println("jdbc_sslmode=" + target.sslMode());
        System.out.println("jdbc_credentials_embedded=" + target.credentialsEmbedded());
        System.out.println("jdbc_url_fragment_present=" + target.fragmentPresent());
        System.out.println("jdbc_separate_credentials_conflict="
                + DatabaseDiagnostics.hasCredentialSourceConflict(target, environment));
        System.out.println("session_pooler_host_shape=" + DatabaseDiagnostics.hasSessionPoolerHostShape(target.host()));
        System.out.println("session_pooler_username_shape="
                + DatabaseDiagnostics.hasSessionPoolerUsernameShape(environment.get("DATABASE_USERNAME")));

        if (!hasArgument(args, "--connect")) {
            System.out.println("postgres_connection=NOT_REQUESTED");
            return;
        }

        if (target.credentialsEmbedded() || target.fragmentPresent()) {
            System.out.println("postgres_connection=SKIPPED; reason=url_credentials_or_fragment");
            return;
        }
        if (!DatabaseDiagnostics.isPresent(environment, "DATABASE_USERNAME")
                || !DatabaseDiagnostics.isPresent(environment, "DATABASE_PASSWORD")) {
            System.out.println("postgres_connection=SKIPPED; reason=separate_credentials_missing");
            return;
        }
        if (!target.requiresEncryptedConnection()) {
            System.out.println("postgres_connection=SKIPPED; reason=sslmode_not_encrypted");
            return;
        }

        try {
            Properties properties = new Properties();
            properties.setProperty("user", environment.get("DATABASE_USERNAME"));
            properties.setProperty("password", environment.get("DATABASE_PASSWORD"));
            properties.setProperty("connectTimeout", "8");
            properties.setProperty("socketTimeout", "8");
            properties.setProperty("sslmode", target.sslMode());
            try (Connection connection = DriverManager.getConnection(environment.get("DATABASE_URL"), properties)) {
                connection.setReadOnly(true);
                try (Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT 1")) {
                    boolean selectedOne = result.next() && result.getInt(1) == 1;
                    System.out.println("postgres_connection=" + (selectedOne ? "PASS" : "FAIL")
                            + "; select_1=" + (selectedOne ? "PASS" : "FAIL") + "; ssl_enforced=true");
                }
            }
        } catch (Throwable failure) {
            System.out.println("postgres_connection=FAIL; " + DatabaseDiagnostics.sanitizeFailure(failure).format());
        }
    }

    private static boolean hasArgument(String[] args, String expected) {
        for (String argument : args) {
            if (expected.equals(argument)) {
                return true;
            }
        }
        return false;
    }
}
