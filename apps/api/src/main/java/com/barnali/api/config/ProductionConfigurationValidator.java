package com.barnali.api.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProductionConfigurationValidator implements ApplicationRunner {
    private final BarnaliProperties properties;
    private final boolean secureSessionCookie;

    public ProductionConfigurationValidator(BarnaliProperties properties,
                                            @Value("${server.servlet.session.cookie.secure:false}") boolean secureSessionCookie) {
        this.properties = properties;
        this.secureSessionCookie = secureSessionCookie;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate(properties.production(), secureSessionCookie, properties.appOrigin(), properties.apiOrigin());
    }

    static void validate(boolean production, boolean secureCookie, String appOrigin, String apiOrigin) {
        if (!production) return;
        if (!secureCookie) throw new IllegalStateException("SESSION_COOKIE_SECURE must be true in production.");
        requireHttpsOrigin("APP_ORIGIN", appOrigin);
        requireHttpsOrigin("API_ORIGIN", apiOrigin);
    }

    private static void requireHttpsOrigin(String name, String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
                throw new IllegalStateException(name + " must be an HTTPS origin without a path, query, or credentials in production.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be a valid HTTPS origin in production.", exception);
        }
    }
}
