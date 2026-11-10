package com.barnali.api.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProductionConfigurationValidatorTest {
    @Test
    void productionRequiresSecureCookieAndHttpsOrigins() {
        assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(true, true,
                "https://community.example", "https://api.example"));
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(true, false,
                "https://community.example", "https://api.example"));
        assertThrows(IllegalStateException.class, () -> ProductionConfigurationValidator.validate(true, true,
                "http://community.example", "https://api.example"));
    }

    @Test
    void developmentDoesNotRequireProductionOrigins() {
        assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(false, false,
                "http://localhost:3000", "http://localhost:8080"));
    }
}
