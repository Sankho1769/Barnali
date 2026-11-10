package com.barnali.api.auth;

import com.barnali.operations.OwnerBootstrapConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Isolated non-web entry point. It never scans or starts the normal API. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(OwnerBootstrapConfiguration.class)
@Profile("barnali-owner-bootstrap-only")
@ConditionalOnProperty(prefix = "barnali.owner.bootstrap", name = "enabled", havingValue = "true")
public class OwnerBootstrapApplication {
    static final String APPROVAL_ENVIRONMENT_VARIABLE = "BARNALI_OWNER_BOOTSTRAP_APPROVED";
    private static final String[] SAFE_ARGUMENTS = {
            "--spring.profiles.active=barnali-owner-bootstrap-only",
            "--barnali.owner.bootstrap.enabled=true",
            "--spring.main.web-application-type=none",
            "--spring.flyway.enabled=false",
            "--logging.level.root=OFF"
    };

    private OwnerBootstrapApplication() {}

    static boolean isApproved(String approval) {
        return "true".equalsIgnoreCase(approval);
    }

    static String[] safeArguments() {
        return SAFE_ARGUMENTS.clone();
    }

    public static void main(String[] ignoredArguments) {
        if (!isApproved(System.getenv(APPROVAL_ENVIRONMENT_VARIABLE))) {
            System.err.println("owner_bootstrap_status=REFUSED");
            System.err.println("detail=Run scripts/bootstrap-owner.ps1 and complete its interactive approval prompt.");
            System.exit(2);
            return;
        }

        SpringApplication application = new SpringApplication(OwnerBootstrapApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        try (ConfigurableApplicationContext ignored = application.run(safeArguments())) {
            System.out.println("owner_bootstrap_status=SUCCESS");
        } catch (Throwable failure) {
            System.err.println("owner_bootstrap_status=FAILED");
            System.err.println(OwnerBootstrapDiagnostics.format(failure));
            System.exit(1);
        }
    }
}
