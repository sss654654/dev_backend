package com.example.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer flywayConfigurationCustomizer(@Qualifier("writeDataSource") DataSource dataSource) {
        return configuration -> {
            configuration.dataSource(dataSource)
                    .baselineOnMigrate(true)
                    .locations("classpath:db/migration");
        };
    }
}