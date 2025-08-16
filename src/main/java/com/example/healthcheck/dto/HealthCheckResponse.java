package com.example.healthcheck.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class HealthCheckResponse {
    
    private String status;
    private LocalDateTime timestamp;
    private Map<String, Object> details;
    
    public HealthCheckResponse() {
        this.timestamp = LocalDateTime.now();
    }
    
    public HealthCheckResponse(String status) {
        this.status = status;
        this.timestamp = LocalDateTime.now();
    }
    
    public HealthCheckResponse(String status, Map<String, Object> details) {
        this.status = status;
        this.details = details;
        this.timestamp = LocalDateTime.now();
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public LocalDateTime getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
    
    public static class Status {
        public static final String UP = "UP";
        public static final String DOWN = "DOWN";
    }
}