package com.barnali.api.config;

import com.barnali.api.security.BarnaliJdbcTransactionManager;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods = false)
public class DatabaseTransactionConfiguration {
    @Bean
    JdbcTransactionManager transactionManager(DataSource dataSource) {
        return new BarnaliJdbcTransactionManager(dataSource);
    }
}
