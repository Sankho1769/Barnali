package com.barnali.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "barnali")
public record BarnaliProperties(
        String environment,
        String appOrigin,
        String apiOrigin,
        String ownerEmail,
        String ownerBootstrapPassword,
        String securityHmacKey,
        String mailFrom,
        String invitationBaseUrl,
        String passwordResetBaseUrl) {
    public boolean production() {
        return "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
    }
}
