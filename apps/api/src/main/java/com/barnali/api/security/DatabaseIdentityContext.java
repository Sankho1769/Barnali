package com.barnali.api.security;

import com.barnali.api.auth.AppPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves database identity only from the server-side Spring Security context. */
public final class DatabaseIdentityContext {
    private static final ThreadLocal<Integer> ANONYMOUS_SCOPE_DEPTH = new ThreadLocal<>();

    private DatabaseIdentityContext() {}

    public static Optional<UUID> authenticatedUserId() {
        Integer depth = ANONYMOUS_SCOPE_DEPTH.get();
        if (depth != null && depth > 0) return Optional.empty();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal.id());
    }

    /** Suppresses a pre-existing web session while a public authentication flow runs. */
    public static AnonymousScope anonymousScope() {
        Integer depth = ANONYMOUS_SCOPE_DEPTH.get();
        ANONYMOUS_SCOPE_DEPTH.set(depth == null ? 1 : depth + 1);
        return new AnonymousScope(Thread.currentThread());
    }

    public static final class AnonymousScope implements AutoCloseable {
        private final Thread owner;
        private boolean closed;

        private AnonymousScope(Thread owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Database identity scope must close on the thread that opened it.");
            }
            Integer depth = ANONYMOUS_SCOPE_DEPTH.get();
            if (depth == null || depth <= 1) ANONYMOUS_SCOPE_DEPTH.remove();
            else ANONYMOUS_SCOPE_DEPTH.set(depth - 1);
            closed = true;
        }
    }
}
