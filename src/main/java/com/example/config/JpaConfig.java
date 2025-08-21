package com.example.config;

import org.springframework.boot.autoconfigure.orm.jpa.JpaProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import javax.sql.DataSource;

@Configuration
public class JpaConfig {

    /**
     * JPA의 EntityManagerFactory를 직접 설정합니다.
     * @DependsOn("flywayInitializer") 어노테이션을 통해
     * Spring Boot가 'flywayInitializer'라는 Bean(Flyway 마이그레이션을 실제 수행하는 Bean)을
     * 먼저 완료시킨 후에 이 Bean을 생성하도록 강제합니다.
     *
     * @param dataSource 데이터 소스
     * @param builder EntityManagerFactoryBuilder
     * @param jpaProperties JPA 속성
     * @return LocalContainerEntityManagerFactoryBean
     */
    @Bean
    @DependsOn("flywayInitializer")
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
            DataSource dataSource,
            EntityManagerFactoryBuilder builder,
            JpaProperties jpaProperties) {
        
        return builder
                .dataSource(dataSource)
                .packages("com.example") // 엔티티 클래스가 있는 기본 패키지를 지정합니다.
                .properties(jpaProperties.getProperties())
                .build();
    }
}