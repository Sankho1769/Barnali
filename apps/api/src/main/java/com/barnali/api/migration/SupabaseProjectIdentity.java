package com.barnali.api.migration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the non-secret project reference supplied independently by the operator. */
public final class SupabaseProjectIdentity {
    private static final Pattern PROJECT_REF = Pattern.compile("^[a-z0-9]{10,64}$");
    private static final Pattern SESSION_POOLER_USERNAME =
            Pattern.compile("^[a-z][a-z0-9_-]{0,62}\\.([a-z0-9]{10,64})$");

    private SupabaseProjectIdentity() {}

    public static void requireUsernameMatches(String username, String expectedProjectRef) {
        if (expectedProjectRef == null || !PROJECT_REF.matcher(expectedProjectRef).matches()) {
            throw new IllegalStateException("The operator-supplied Supabase project reference is missing or invalid.");
        }
        Matcher matcher = username == null ? null : SESSION_POOLER_USERNAME.matcher(username);
        if (matcher == null || !matcher.matches()) {
            throw new IllegalStateException("A database username is not in the Supabase Session Pooler role/project format.");
        }
        if (!expectedProjectRef.equals(matcher.group(1))) {
            throw new IllegalStateException("A configured database principal does not match the operator-supplied Supabase project reference.");
        }
    }
}
