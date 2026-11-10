package com.barnali.api.auth;

import com.barnali.api.config.ApiError;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionUserValidationFilter extends OncePerRequestFilter {
    private final CurrentIdentityService identity;
    private final ObjectMapper mapper;

    public SessionUserValidationFilter(CurrentIdentityService identity, ObjectMapper mapper) {
        this.identity = identity;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AppPrincipal principal) {
            try {
                if (!identity.isActive(principal)) {
                    SecurityContextHolder.clearContext();
                    if (request.getSession(false) != null) request.getSession(false).invalidate();
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType("application/json");
                    mapper.writeValue(response.getOutputStream(), ApiError.of("SESSION_REVOKED", "Your session is no longer active. Sign in again."));
                    return;
                }
            } catch (RuntimeException exception) {
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), ApiError.of("AUTH_SERVICE_UNAVAILABLE", "Authentication is temporarily unavailable."));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
