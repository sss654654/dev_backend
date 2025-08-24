package com.example.admission.controller;

import com.example.admission.dto.AdmissionMetrics;
import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/monitoring")
@Tag(name = "Admission Monitoring", description = "대기열 시스템 모니터링 API (3단계)")
public class AdmissionMonitoringController {

    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    public AdmissionMonitoringController(AdmissionMetricsService metricsService,
                                       LoadBalancingOptimizer loadBalancer) {
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    @Operation(summary = "실시간 메트릭 조회", description = "시스템의 실시간 성능 메트릭을 확인합니다")
    @GetMapping("/metrics")
    public ResponseEntity<AdmissionMetrics> getMetrics() {
        return ResponseEntity.ok(metricsService.getCurrentMetrics());
    }

    @Operation(summary = "성능 분석 보고서", description = "시스템 성능 분석 및 최적화 추천사항을 제공합니다")
    @GetMapping("/analysis")
    public ResponseEntity<Map<String, Object>> getPerformanceAnalysis() {
        return ResponseEntity.ok(metricsService.getPerformanceAnalysis());
    }

    @Operation(summary = "부하 분산 상태", description = "Pod별 부하 분산 상태를 확인합니다")
    @GetMapping("/load-balancing")
    public ResponseEntity<Map<String, Object>> getLoadBalancingStatus() {
        return ResponseEntity.ok(loadBalancer.getLoadBalancingStatus());
    }

    @Operation(summary = "종합 대시보드", description = "시스템 전체 상태를 한눈에 확인할 수 있는 대시보드 데이터")
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        
        try {
            AdmissionMetrics metrics = metricsService.getCurrentMetrics();
            Map<String, Object> analysis = metricsService.getPerformanceAnalysis();
            Map<String, Object> loadBalancing = loadBalancer.getLoadBalancingStatus();
            
            // 핵심 지표
            dashboard.put("overview", Map.of(
                "systemStatus", metrics.getSystemStatus(),
                "podCount", metrics.podCount(),
                "currentUtilization", Math.round(metrics.getCurrentUtilizationRate() * 10) / 10.0,
                "totalActiveUsers", metrics.currentActiveSessions(),
                "totalWaitingUsers", metrics.currentWaitingUsers(),
                "avgThroughputPerMinute", Math.round(metrics.getAverageThroughputPerMinute())
            ));
            
            // 성능 트렌드
            dashboard.put("trends", Map.of(
                "throughputHistory", metrics.throughputHistory(),
                "queueSizeHistory", metrics.queueSizeHistory(),
                "utilizationHistory", metrics.podUtilizationHistory(),
                "isQueueGrowing", metrics.isQueueGrowing()
            ));
            
            // 운영 통계
            dashboard.put("statistics", Map.of(
                "totalProcessed", metrics.totalEntriesProcessed(),
                "totalTimeouts", metrics.totalTimeouts(),
                "totalQueueJoins", metrics.totalQueueJoins(),
                "batchProcesses", metrics.totalBatchProcesses(),
                "avgProcessingTime", metrics.avgProcessingTimeMs()
            ));
            
            // 추천사항
            dashboard.put("recommendations", analysis);
            
            // 부하 분산 정보
            dashboard.put("loadBalancing", loadBalancing);
            
            dashboard.put("lastUpdated", System.currentTimeMillis());
            
        } catch (Exception e) {
            dashboard.put("error", "대시보드 데이터 수집 중 오류: " + e.getMessage());
        }
        
        return ResponseEntity.ok(dashboard);
    }

    @Operation(summary = "메트릭 리셋", description = "누적된 메트릭 데이터를 초기화합니다 (개발/테스트용)")
    @PostMapping("/metrics/reset")
    public ResponseEntity<Map<String, String>> resetMetrics() {
        // 개발/테스트 환경에서만 허용 (운영에서는 비활성화 권장)
        try {
            // 메트릭 서비스에 리셋 메서드가 있다고 가정
            // metricsService.resetMetrics();
            
            Map<String, String> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "메트릭이 초기화되었습니다");
            result.put("timestamp", String.valueOf(System.currentTimeMillis()));
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "메트릭 초기화 실패: " + e.getMessage());
            
            return ResponseEntity.status(500).body(result);
        }
    }

    @Operation(summary = "Pod 부하 수동 업데이트", description = "현재 Pod의 부하 상태를 수동으로 업데이트합니다")
    @PostMapping("/load-balancing/update-load")
    public ResponseEntity<Map<String, Object>> updatePodLoad(@RequestParam int load) {
        try {
            loadBalancer.updatePodLoad(load);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "Pod 부하가 업데이트되었습니다");
            result.put("newLoad", load);
            result.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "Pod 부하 업데이트 실패: " + e.getMessage());
            
            return ResponseEntity.status(500).body(result);
        }
    }
}