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
     * @DependsOn("flyway") 어노테이션을 통해
     * Spring에게 'flyway'라는 이름의 Bean(FlywayConfig에서 만든 Bean)이
     * 완전히 생성된 후에 이 entityManagerFactory Bean을 생성하도록 강제합니다.
     * 이로써 순환 참조가 원천적으로 해결됩니다.
     */
    @Bean
    @DependsOn("flyway")
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