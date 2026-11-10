package com.barnali.api.auth;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** Emits allow-listed failure metadata only; never includes messages or stack traces. */
final class OwnerBootstrapDiagnostics {
    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z0-9_$]{1,80}");

    private OwnerBootstrapDiagnostics() {}

    static String format(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        String sqlState = "unavailable";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12 && seen.add(current); depth++) {
            if (!chain.isEmpty()) chain.append('>');
            String simpleName = current.getClass().getSimpleName();
            chain.append(TYPE_NAME.matcher(simpleName).matches() ? simpleName : "Exception");
            if (sqlState.equals("unavailable") && current instanceof SQLException sqlException) {
                String candidate = sqlException.getSQLState();
                if (candidate != null && SQL_STATE.matcher(candidate).matches()) sqlState = candidate;
            }
            if (sqlState.equals("unavailable") && current instanceof SafeDatabaseException safeDatabaseException) {
                String candidate = safeDatabaseException.sqlState();
                if (candidate != null && SQL_STATE.matcher(candidate).matches()) sqlState = candidate;
            }
            current = current.getCause();
        }
        if (chain.isEmpty()) chain.append("Exception");
        return "exception_chain=" + chain + "\nsqlstate=" + sqlState;
    }

    static final class SafeDatabaseException extends RuntimeException {
        SafeDatabaseException(String sqlState) {
            super("The read-only owner-bootstrap preflight failed.");
            this.sqlState = sqlState != null && SQL_STATE.matcher(sqlState).matches() ? sqlState : "unavailable";
        }

        private final String sqlState;

        String sqlState() {
            return sqlState;
        }
    }
}
