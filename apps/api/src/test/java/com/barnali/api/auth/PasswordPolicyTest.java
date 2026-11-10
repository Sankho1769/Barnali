package com.barnali.api.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.barnali.api.config.ApiException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    @Test
    void acceptsStrongUtf8PassphraseWithinBcryptByteLimit() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("Curious minds grow! 2046"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("🙂".repeat(18)));
    }

    @Test
    void rejectsShortCommonAndOverlongUtf8Passwords() {
        assertFalse(PasswordPolicy.isValid("short"));
        assertFalse(PasswordPolicy.isValid("Password starts with a common word"));
        assertFalse(PasswordPolicy.isValid("Welcome to this uncommon password"));
        assertFalse(PasswordPolicy.isValid("Admin123 begins with a common pattern"));
        assertFalse(PasswordPolicy.isValid("é".repeat(40)));
        assertFalse(PasswordPolicy.isValid(null));
        assertThrows(ApiException.class, () -> PasswordPolicy.validate("é".repeat(40)));
    }
}
