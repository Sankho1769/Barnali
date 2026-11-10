package com.barnali.operations;

import com.barnali.api.auth.OwnerBootstrapReadiness;
import com.barnali.api.auth.OwnerBootstrapRunner;
import com.barnali.api.config.BarnaliProperties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Beans loaded only by the explicitly imported, non-web owner bootstrap entry point. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BarnaliProperties.class)
@Profile("barnali-owner-bootstrap-only")
@ConditionalOnProperty(prefix = "barnali.owner.bootstrap", name = "enabled", havingValue = "true")
public class OwnerBootstrapConfiguration {
    @Bean
    PasswordEncoder ownerBootstrapPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Primary
    DataSource ownerProvisionerDataSource(Environment environment) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(environment.getProperty("barnali.owner.provisioner.database-url", ""));
        dataSource.setUsername(environment.getProperty("barnali.owner.provisioner.database-username", ""));
        dataSource.setPassword(environment.getProperty("barnali.owner.provisioner.database-password", ""));
        return dataSource;
    }

    @Bean
    @Primary
    JdbcTemplate ownerProvisionerJdbcTemplate(@Qualifier("ownerProvisionerDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    OwnerBootstrapRunner ownerBootstrapRunner(JdbcTemplate jdbc, PasswordEncoder ownerBootstrapPasswordEncoder,
            BarnaliProperties properties) {
        return new OwnerBootstrapRunner(jdbc, ownerBootstrapPasswordEncoder, properties);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner ownerBootstrapReadiness(DataSource dataSource, Environment environment) {
        return (ApplicationArguments ignored) -> OwnerBootstrapReadiness.verify(dataSource, environment);
    }
}
