package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.pod.service.PodDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/admission")
@Tag(name = "Admission Admin", description = "대기열 시스템 관리 API")
public class AdmissionAdminController {

    private final AdmissionService admissionService;
    private final PodDiscoveryService podDiscoveryService;
    private final DynamicSessionCalculator sessionCalculator;

    public AdmissionAdminController(AdmissionService admissionService,
                                  PodDiscoveryService podDiscoveryService,
                                  DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.podDiscoveryService = podDiscoveryService;
        this.sessionCalculator = sessionCalculator;
    }

    @Operation(summary = "현재 세션 설정 조회", description = "동적 스케일링 설정과 현재 Pod 수를 확인합니다")
    @GetMapping("/config")
    public ResponseEntity<DynamicSessionCalculator.SessionCalculationInfo> getAdmissionConfig() {
        return ResponseEntity.ok(sessionCalculator.getCalculationInfo());
    }

    @Operation(summary = "Pod 수 강제 새로고침", description = "Kubernetes에서 Pod 수를 즉시 다시 조회합니다")
    @PostMapping("/refresh-pods")
    public ResponseEntity<Map<String, Object>> refreshPods() {
        int previousCount = podDiscoveryService.getCurrentPodCount();
        
        // 강제로 Pod 수 업데이트
        podDiscoveryService.updatePodCount();
        
        int currentCount = podDiscoveryService.getCurrentPodCount();
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        
        Map<String, Object> response = new HashMap<>();
        response.put("previousPodCount", previousCount);
        response.put("currentPodCount", currentCount);
        response.put("calculatedMaxSessions", maxSessions);
        response.put("refreshed", true);
        
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "영화별 현재 활성 세션 수 조회", description = "특정 영화의 현재 활성 세션 수를 확인합니다")
    @GetMapping("/active-sessions/{movieId}")
    public ResponseEntity<Map<String, Object>> getActiveSessionsInfo(@PathVariable String movieId) {
        // movieId 예시: "movie-avatar3", "movie-spiderman2" 등
        long currentActiveSessions = admissionService.getCurrentActiveSessionsCount("movie", movieId);
        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        long vacantSlots = admissionService.getVacantSlots("movie", movieId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("movieId", movieId);
        response.put("currentActiveSessions", currentActiveSessions);
        response.put("maxActiveSessions", maxActiveSessions);
        response.put("vacantSlots", vacantSlots);
        response.put("utilizationRate", maxActiveSessions > 0 ? 
            (double) currentActiveSessions / maxActiveSessions * 100 : 0);
        
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "전체 시스템 상태 조회", description = "Pod 수, 세션 설정, Kubernetes 연결 상태를 모두 확인합니다")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        var config = sessionCalculator.getCalculationInfo();
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("podCount", config.currentPodCount());
        response.put("baseSessionsPerPod", config.baseSessionsPerPod());
        response.put("maxActiveSessions", config.calculatedMaxSessions());
        response.put("maxTotalSessionsLimit", config.maxTotalSessionsLimit());
        response.put("dynamicScalingEnabled", config.dynamicScalingEnabled());
        response.put("kubernetesAvailable", config.kubernetesAvailable());
        response.put("status", config.kubernetesAvailable() ? "CONNECTED" : "FALLBACK_MODE");
        
        return ResponseEntity.ok(response);
    }
}