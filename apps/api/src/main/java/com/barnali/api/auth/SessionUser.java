package com.barnali.api.auth;

import java.util.List;
import java.util.UUID;

public record SessionUser(
        UUID id,
        String username,
        String displayName,
        String email,
        String role,
        List<String> permissions,
        boolean mustChangePassword) {}
