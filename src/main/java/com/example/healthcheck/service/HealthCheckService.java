package com.example.healthcheck.service;

import com.example.healthcheck.dto.HealthCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Service
public class HealthCheckService {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);
    
    private final DataSource writeDataSource;
    private final DataSource readDataSource;
    
    public HealthCheckService(@Qualifier("writeDataSource") DataSource writeDataSource,
                             @Qualifier("readDataSource") DataSource readDataSource) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
    }
    
    public HealthCheckResponse checkHealth() {
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("application", "UP");
            
            return new HealthCheckResponse(HealthCheckResponse.Status.UP, details);
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN);
        }
    }
    
    public HealthCheckResponse checkReadiness() {
        try {
            Map<String, Object> details = new HashMap<>();
            
            boolean writeDbUp = checkDatabaseConnection(writeDataSource, "write");
            boolean readDbUp = checkDatabaseConnection(readDataSource, "read");
            
            details.put("writeDatabase", writeDbUp ? "UP" : "DOWN");
            details.put("readDatabase", readDbUp ? "UP" : "DOWN");
            details.put("application", "UP");
            
            if (writeDbUp && readDbUp) {
                return new HealthCheckResponse(HealthCheckResponse.Status.UP, details);
            } else {
                return new HealthCheckResponse(HealthCheckResponse.Status.DOWN, details);
            }
        } catch (Exception e) {
            logger.error("Readiness check failed", e);
            Map<String, Object> details = new HashMap<>();
            details.put("error", e.getMessage());
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN, details);
        }
    }
    
    public HealthCheckResponse checkLiveness() {
        try {
            return new HealthCheckResponse(HealthCheckResponse.Status.UP);
        } catch (Exception e) {
            logger.error("Liveness check failed", e);
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN);
        }
    }
    
    private boolean checkDatabaseConnection(DataSource dataSource, String dbType) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            logger.warn("Database connection check failed for {}: {}", dbType, e.getMessage());
            return false;
        }
    }
}