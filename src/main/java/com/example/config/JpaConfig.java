package com.example.config;

import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import javax.sql.DataSource;

@Configuration
public class JpaConfig {

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource, // @Primary로 지정된 최종 DataSource가 주입됩니다.
            EntityManagerFactoryBuilder builder,
            JpaProperties jpaProperties) {
        
        return builder
                .dataSource(dataSource)
                .packages("com.example") // 엔티티 클래스가 있는 패키지
                .properties(jpaProperties.getProperties())
                .build();
    }
}