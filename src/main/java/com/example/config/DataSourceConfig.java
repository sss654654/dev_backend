package com.example.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
@Configuration
public class DataSourceConfig {

    // Writer 풀
    @Bean(name = "writeDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.write") // 아래 YAML 참조
    public DataSource writeDataSource() {
        return new HikariDataSource();
    }

    // Reader 풀
    @Bean(name = "readDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.read")
    public DataSource readDataSource() {
        return new HikariDataSource();
    }

    // 라우팅 DataSource (키: "write", "read")
    @Bean(name = "routingDelegate")
    public DataSource routingDelegate(@Qualifier("writeDataSource") DataSource write,
                                      @Qualifier("readDataSource") DataSource read) {
        RoutingDataSource rds = new RoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put("write", write);
        targets.put("read", read);
        rds.setDefaultTargetDataSource(write);   // 기본은 write
        rds.setTargetDataSources(targets);
        rds.afterPropertiesSet();
        return rds;
    }

    // 반드시 Lazy 프록시로 감싸서 "트랜잭션 경계에서" 라우팅 결정되도록!
    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource(@Qualifier("routingDelegate") DataSource routing) {
        return new LazyConnectionDataSourceProxy(routing);
    }
}
