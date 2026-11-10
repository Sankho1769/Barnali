package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.barnali.api.auth.AppPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class DatabaseIdentityContextTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsOnlyTheAuthenticatedApplicationPrincipal() {
        UUID expected = UUID.randomUUID();
        AppPrincipal principal = principal(expected);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(expected);
    }

    @Test
    void rejectsUnauthenticatedAndNonApplicationPrincipals() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(UUID.randomUUID().toString(), null));
        assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client-supplied-value", null, java.util.List.of()));
        assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
    }

    @Test
    void anonymousScopesNestAndAlwaysRestoreTheAuthenticatedPrincipal() {
        UUID expected = UUID.randomUUID();
        AppPrincipal principal = principal(expected);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        try (var outer = DatabaseIdentityContext.anonymousScope()) {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            try (var inner = DatabaseIdentityContext.anonymousScope()) {
                assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            }
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
        }

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(expected);
    }

    @Test
    void anonymousScopeIsRemovedWhenTheFlowThrows() {
        UUID expected = UUID.randomUUID();
        AppPrincipal principal = principal(expected);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        try {
            try (var ignored = DatabaseIdentityContext.anonymousScope()) {
                throw new IllegalStateException("synthetic failure");
            }
        } catch (IllegalStateException expectedFailure) {
            assertThat(expectedFailure).hasMessage("synthetic failure");
        }

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(expected);
    }

    private static AppPrincipal principal(UUID id) {
        return new AppPrincipal(id, "member@example.invalid", "member", "Member", "", "ACTIVE", false, 0);
    }
}
