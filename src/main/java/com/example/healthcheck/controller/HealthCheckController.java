package com.example.healthcheck.controller;

import com.example.healthcheck.dto.HealthCheckResponse;
import com.example.healthcheck.service.HealthCheckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthCheckController {
    
    private final HealthCheckService healthCheckService;
    
    @Autowired
    public HealthCheckController(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }
    
    @GetMapping
    public ResponseEntity<HealthCheckResponse> health() {
        HealthCheckResponse response = healthCheckService.checkHealth();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
    
    @GetMapping("/ready")
    public ResponseEntity<HealthCheckResponse> readiness() {
        HealthCheckResponse response = healthCheckService.checkReadiness();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
    
    @GetMapping("/live")
    public ResponseEntity<HealthCheckResponse> liveness() {
        HealthCheckResponse response = healthCheckService.checkLiveness();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}