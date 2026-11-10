package com.barnali.api.auth;

import com.barnali.api.admin.AdminAuditService;
import com.barnali.api.config.ApiException;
import com.barnali.api.security.DatabaseIdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final LoginRateLimitService rateLimits;
    private final AccountService accounts;
    private final PasswordRecoveryService recovery;
    private final AdminAuditService audit;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final CsrfTokenRepository csrfRepository;

    public AuthController(AuthenticationManager authenticationManager, LoginRateLimitService rateLimits,
                          AccountService accounts, PasswordRecoveryService recovery, AdminAuditService audit,
                          SessionAuthenticationStrategy sessionStrategy, SecurityContextRepository contextRepository,
                          CsrfTokenRepository csrfRepository) {
        this.authenticationManager = authenticationManager;
        this.rateLimits = rateLimits;
        this.accounts = accounts;
        this.recovery = recovery;
        this.audit = audit;
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.csrfRepository = csrfRepository;
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 80) String displayName,
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "[A-Za-z0-9_]+") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {}

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 72) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(min = 12, max = 72) String newPassword) {}
    public record RecoveryRequest(@NotBlank @Email @Size(max = 254) String email) {}
    public record ResetRequest(@NotBlank @Size(max = 200) String token,
                               @NotBlank @Size(min = 12, max = 72) String password) {}

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody RegisterRequest input, HttpServletRequest request) {
        try (var ignored = DatabaseIdentityContext.anonymousScope()) {
            String ip = request.getRemoteAddr();
            if (rateLimits.isBlocked("register", input.email(), ip)) throw ApiException.tooManyRequests();
            rateLimits.recordAttempt("register", input.email(), ip);
            accounts.register(input.displayName(), input.username(), input.email(), input.password());
            return Map.of("message", "Account created. Sign in to continue.");
        }
    }

    @PostMapping("/login")
    public SessionUser login(@Valid @RequestBody LoginRequest input, HttpServletRequest request,
                             HttpServletResponse response) {
        String email = input.email().trim().toLowerCase();
        String remoteAddress = request.getRemoteAddr();
        Authentication authentication;
        try (var ignored = DatabaseIdentityContext.anonymousScope()) {
            if (rateLimits.isBlocked("login", email, remoteAddress)) {
                if (rateLimits.shouldAuditBlockedAddress("login", remoteAddress)) {
                    audit.record(null, "LOGIN_THROTTLED", "auth", null, "DENIED", null, request.getHeader("X-Request-ID"), Map.of());
                }
                throw ApiException.tooManyRequests();
            }
            try {
                authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, input.password()));
            } catch (AuthenticationException exception) {
                rateLimits.recordAttempt("login", email, remoteAddress);
                String action = exception instanceof DisabledException ? "LOGIN_INACTIVE" : "LOGIN_FAILED";
                audit.record(null, action, "auth", null, "FAILURE", null, request.getHeader("X-Request-ID"), Map.of());
                throw ApiException.unauthorized("Email or password is incorrect.");
            }
            rateLimits.clear("login", email, remoteAddress);
        }

        AppPrincipal principal = (AppPrincipal) authentication.getPrincipal();
        request.getSession(true);
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        accounts.recordLogin();
        audit.record(principal.id(), "LOGIN_SUCCESS", "auth", null, "SUCCESS", null, request.getHeader("X-Request-ID"), Map.of());
        return accounts.sessionUser(principal);
    }

    @GetMapping("/me")
    public SessionUser me(Authentication authentication) {
        return accounts.sessionUser((AppPrincipal) authentication.getPrincipal());
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppPrincipal principal) {
            audit.record(principal.id(), "LOGOUT", "auth", null, "SUCCESS", null, request.getHeader("X-Request-ID"), Map.of());
        }
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        csrfRepository.saveToken(null, request, response);
        return Map.of("message", "Signed out.");
    }

    @PostMapping("/password/change")
    public Map<String, String> changePassword(@Valid @RequestBody ChangePasswordRequest input,
                                               Authentication authentication, HttpServletRequest request) {
        AppPrincipal principal = (AppPrincipal) authentication.getPrincipal();
        accounts.changePassword(input.currentPassword(), input.newPassword(), request.getHeader("X-Request-ID"));
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("message", "Password changed. Sign in again with your new password.");
    }

    @PostMapping("/password/recovery")
    public Map<String, String> requestRecovery(@Valid @RequestBody RecoveryRequest input, HttpServletRequest request) {
        try (var ignored = DatabaseIdentityContext.anonymousScope()) {
            recovery.requestReset(input.email(), request.getRemoteAddr());
            return Map.of("message", "If the account exists and recovery delivery is enabled, instructions will be sent.");
        }
    }

    @PostMapping("/password/reset")
    public Map<String, String> resetPassword(@Valid @RequestBody ResetRequest input, HttpServletRequest request) {
        try (var ignored = DatabaseIdentityContext.anonymousScope()) {
            recovery.reset(input.token(), input.password(), request.getHeader("X-Request-ID"));
            return Map.of("message", "Password updated. Sign in with your new password.");
        }
    }
}
