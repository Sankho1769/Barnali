package com.barnali.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.barnali.api.admin.AdminAuthorization;
import com.barnali.api.admin.AdminService;
import com.barnali.api.admin.AdminTeamService;
import com.barnali.api.auth.AccountService;
import com.barnali.api.auth.AppPrincipal;
import com.barnali.api.auth.AppUserDetailsService;
import com.barnali.api.auth.CurrentIdentityService;
import com.barnali.api.auth.LoginRateLimitService;
import com.barnali.api.auth.PasswordRecoveryService;
import com.barnali.api.community.CommunityService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

class ProtectedQueryTransactionBoundaryTest {
    @Test
    void authenticatedAndAdministrativeReadsAreTransactionBound() throws Exception {
        assertReadOnlyTransaction(CurrentIdentityService.class.getMethod("current", AppPrincipal.class));
        assertReadOnlyTransaction(CurrentIdentityService.class.getMethod("isActive", AppPrincipal.class));
        assertReadOnlyTransaction(AccountService.class.getMethod("sessionUser", AppPrincipal.class));
        assertReadOnlyTransaction(AppUserDetailsService.class.getMethod("loadUserByUsername", String.class));
        assertReadOnlyTransaction(CommunityService.class.getMethod("myRooms", AppPrincipal.class));
        assertReadOnlyTransaction(AdminAuthorization.class.getMethod("has", Authentication.class, String.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("analytics", LocalDate.class, LocalDate.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("overview", LocalDate.class, LocalDate.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("users", String.class, String.class, int.class,
                int.class, String.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("user", UUID.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("reports", String.class, int.class, int.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("auditLog", String.class, int.class, int.class));
        assertReadOnlyTransaction(AdminService.class.getMethod("rooms"));
        assertReadOnlyTransaction(AdminTeamService.class.getMethod("admins"));
        assertReadOnlyTransaction(AdminTeamService.class.getMethod("invitations"));
        assertReadOnlyTransaction(LoginRateLimitService.class.getMethod("isBlocked", String.class, String.class, String.class));
        assertTransaction(PasswordRecoveryService.class.getMethod("requestReset", String.class, String.class));
        assertTransaction(PasswordRecoveryService.class.getMethod("reset", String.class, String.class, String.class));
    }

    @Test
    void sessionAndMembershipLookupsAcceptAuthenticatedPrincipalsInsteadOfArbitraryUserIds() throws Exception {
        assertThat(AccountService.class.getMethod("sessionUser", AppPrincipal.class)).isNotNull();
        assertThat(CurrentIdentityService.class.getMethod("current", AppPrincipal.class)).isNotNull();
        assertThat(CurrentIdentityService.class.getMethod("isActive", AppPrincipal.class)).isNotNull();
        assertThat(CommunityService.class.getMethod("myRooms", AppPrincipal.class)).isNotNull();

        assertThat(AccountService.class.getMethods()).noneMatch(method -> method.getName().equals("sessionUser")
                && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == UUID.class);
        assertThat(CurrentIdentityService.class.getMethods()).noneMatch(method ->
                (method.getName().equals("current") || method.getName().equals("isActive"))
                        && method.getParameterTypes().length > 0
                        && method.getParameterTypes()[0] == UUID.class);
        assertThat(CommunityService.class.getMethods()).noneMatch(method -> method.getName().equals("myRooms")
                && method.getParameterTypes().length == 1 && method.getParameterTypes()[0] == UUID.class);
    }

    private static void assertReadOnlyTransaction(Method method) {
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertThat(transaction).as("transaction boundary for %s", method).isNotNull();
        assertThat(transaction.readOnly()).as("read-only boundary for %s", method).isTrue();
    }

    private static void assertTransaction(Method method) {
        assertThat(method.getAnnotation(Transactional.class)).as("transaction boundary for %s", method).isNotNull();
    }
}
