package com.example.healthcheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;

@Schema(description = "헬스체크 응답")
public class HealthCheckResponse {
    
    @Schema(description = "애플리케이션 상태", example = "UP", allowableValues = {"UP", "DOWN"})
    private String status;
    @Schema(description = "체크 시간", example = "2024-01-01T12:00:00")
    private LocalDateTime timestamp;
    @Schema(description = "상세 정보", example = "{\"database\": \"UP\", \"application\": \"UP\"}")
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
    
    @Schema(description = "상태 상수")
    public static class Status {
        @Schema(description = "정상 상태")
        public static final String UP = "UP";
        @Schema(description = "비정상 상태")
        public static final String DOWN = "DOWN";
    }
}