package com.example.healthcheck.controller;

import com.example.healthcheck.dto.HealthCheckResponse;
import com.example.healthcheck.service.HealthCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
@Tag(name = "Health Check", description = "헬스체크 API")
public class HealthCheckController {
    
    private final HealthCheckService healthCheckService;
    
    @Autowired
    public HealthCheckController(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }
    
    @Operation(summary = "기본 헬스체크", description = "애플리케이션의 기본 상태를 확인합니다")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "애플리케이션 정상",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class))),
            @ApiResponse(responseCode = "503", description = "애플리케이션 비정상",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class)))
    })
    @GetMapping
    public ResponseEntity<HealthCheckResponse> health() {
        HealthCheckResponse response = healthCheckService.checkHealth();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
    
    @Operation(summary = "시작&준비상태 체크 (Startup&Readiness Probe)", description = "데이터베이스 연결 등 서비스 준비 상태를 확인합니다")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "서비스 준비 완료",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class))),
            @ApiResponse(responseCode = "503", description = "서비스 준비 미완료",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class)))
    })
    @GetMapping("/ready")
    public ResponseEntity<HealthCheckResponse> readiness() {
        HealthCheckResponse response = healthCheckService.checkReadiness();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
    
    @Operation(summary = "생존상태 체크 (Liveness Probe)", description = "애플리케이션이 살아있는지 확인합니다")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "애플리케이션 생존",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class))),
            @ApiResponse(responseCode = "503", description = "애플리케이션 비정상",
                    content = @Content(schema = @Schema(implementation = HealthCheckResponse.class)))
    })
    @GetMapping("/live")
    public ResponseEntity<HealthCheckResponse> liveness() {
        HealthCheckResponse response = healthCheckService.checkLiveness();
        HttpStatus status = HealthCheckResponse.Status.UP.equals(response.getStatus()) 
            ? HttpStatus.OK 
            : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/force-fail-503")
    public ResponseEntity<String> forceFail() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Forced failure - return 503");
    }
}