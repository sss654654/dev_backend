package com.example.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    /**
     * Flyway가 데이터베이스 마이그레이션을 수행하도록 설정합니다.
     * 이 Bean의 이름은 'flyway'가 됩니다.
     * @Qualifier("writeDataSource")를 통해 여러 DataSource 중 쓰기 전용 DB에만
     * 스키마 변경을 수행하도록 명시적으로 지정합니다.
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("writeDataSource") DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load();
    }
}