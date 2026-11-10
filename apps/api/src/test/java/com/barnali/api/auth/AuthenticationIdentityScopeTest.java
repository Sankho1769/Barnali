package com.barnali.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.barnali.api.admin.AdminAuditService;
import com.barnali.api.admin.AdminTeamService;
import com.barnali.api.admin.InvitationController;
import com.barnali.api.security.DatabaseIdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

class AuthenticationIdentityScopeTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registrationAndRecoveryFlowsSuppressAnyExistingSessionIdentity() {
        AppPrincipal previous = principal(UUID.randomUUID());
        authenticate(previous);
        LoginRateLimitService limits = mock(LoginRateLimitService.class);
        AccountService accounts = mock(AccountService.class);
        PasswordRecoveryService recovery = mock(PasswordRecoveryService.class);
        HttpServletRequest request = request();
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(limits.isBlocked(any(), any(), any())).thenAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return false;
        });
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(accounts).register(anyString(), anyString(), anyString(), anyString());
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(recovery).requestReset(anyString(), anyString());
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(recovery).reset(anyString(), anyString(), anyString());
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(limits).recordAttempt(anyString(), anyString(), anyString());

        AuthController controller = controller(mock(AuthenticationManager.class), limits, accounts, recovery);
        controller.register(new AuthController.RegisterRequest("Member", "new_member", "member@example.invalid", "Long test password 123"), request);
        controller.requestRecovery(new AuthController.RecoveryRequest("member@example.invalid"), request);
        controller.resetPassword(new AuthController.ResetRequest("opaque-reset-token-value", "Long test password 456"), request);

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(previous.id());
    }

    @Test
    void loginLookupRunsAnonymousThenAuthenticatedUpdatesUseTheNewPrincipal() throws Exception {
        AppPrincipal oldSession = principal(UUID.randomUUID());
        AppPrincipal signedIn = principal(UUID.randomUUID());
        authenticate(oldSession);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        LoginRateLimitService limits = mock(LoginRateLimitService.class);
        AccountService accounts = mock(AccountService.class);
        PasswordRecoveryService recovery = mock(PasswordRecoveryService.class);
        HttpServletRequest request = request();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(limits.isBlocked(any(), any(), any())).thenAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return false;
        });
        Authentication result = new UsernamePasswordAuthenticationToken(signedIn, null, List.of());
        when(authenticationManager.authenticate(any(Authentication.class))).thenAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return result;
        });
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(limits).clear(anyString(), anyString(), anyString());
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(signedIn.id());
            return null;
        }).when(accounts).recordLogin();
        when(accounts.sessionUser(signedIn)).thenReturn(null);

        AuthController controller = new AuthController(authenticationManager, limits, accounts, recovery,
                mock(AdminAuditService.class), mock(SessionAuthenticationStrategy.class),
                mock(SecurityContextRepository.class), mock(CsrfTokenRepository.class));

        controller.login(new AuthController.LoginRequest("member@example.invalid", "not-a-real-secret"), request, response);

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(signedIn.id());
    }

    @Test
    void invitationAcceptanceUsesAnonymousIdentityEvenWithAnExistingSession() {
        AppPrincipal previous = principal(UUID.randomUUID());
        authenticate(previous);
        AdminTeamService team = mock(AdminTeamService.class);
        doAnswer(call -> {
            assertThat(DatabaseIdentityContext.authenticatedUserId()).isEmpty();
            return null;
        }).when(team).accept("opaque-invitation-token-value", "Admin", "new_admin", "Long test password 123");

        new InvitationController(team).accept(new InvitationController.AcceptInput(
                "opaque-invitation-token-value", "Admin", "new_admin", "Long test password 123"));

        assertThat(DatabaseIdentityContext.authenticatedUserId()).contains(previous.id());
    }

    private static AuthController controller(AuthenticationManager manager, LoginRateLimitService limits,
            AccountService accounts, PasswordRecoveryService recovery) {
        return new AuthController(manager, limits, accounts, recovery, mock(AdminAuditService.class),
                mock(SessionAuthenticationStrategy.class), mock(SecurityContextRepository.class),
                mock(CsrfTokenRepository.class));
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    private static AppPrincipal principal(UUID id) {
        return new AppPrincipal(id, "member@example.invalid", "member", "Member", "", "ACTIVE", false, 0);
    }

    private static void authenticate(AppPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

}
