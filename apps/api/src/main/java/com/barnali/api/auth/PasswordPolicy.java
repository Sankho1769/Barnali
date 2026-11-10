package com.barnali.api.auth;

import com.barnali.api.config.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Shared password rules for member accounts, owner bootstrap, and admin invitations. */
public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static boolean isValid(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return false;
        }
        String normalized = password.toLowerCase(Locale.ROOT);
        return !normalized.startsWith("password")
                && !normalized.startsWith("welcome")
                && !normalized.startsWith("admin123");
    }

    public static void validate(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("PASSWORD_POLICY",
                    "Use a passphrase with at least 12 characters and no more than 72 UTF-8 bytes.");
        }
        if (!isValid(password)) {
            throw ApiException.badRequest("PASSWORD_POLICY", "Choose a less common passphrase.");
        }
    }
}
