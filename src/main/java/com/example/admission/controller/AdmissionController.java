// src/main/java/com/example/admission/controller/AdmissionController.java
package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
import com.example.admission.dto.LeaveRequest;
import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;

@RestController
@RequestMapping("/api/admission")
@Tag(name = "Admission API", description = "대기열 관리 API")
public class AdmissionController {
    
    private static final Logger logger = LoggerFactory.getLogger(AdmissionController.class);
    private final AdmissionService admissionService;
    private final DynamicSessionCalculator sessionCalculator;
    
    public AdmissionController(AdmissionService admissionService, 
                             DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;
    }
    @Value("${SESSION_TIMEOUT_SECONDS:30}")
    private long sessionTimeoutSeconds;


    @Operation(summary = "대기열 진입", description = "영화 예매 대기열에 진입합니다")
    @PostMapping("/enter")
    public ResponseEntity<EnterResponse> enter(@RequestBody EnterRequest request) {
        try {
            EnterResponse response = admissionService.enter("movie", request.movieId(), 
                                                          request.sessionId(), request.requestId());
            
            return (response.getStatus() == EnterResponse.Status.SUCCESS)
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
            
        } catch (Exception e) {
            logger.error("❌ 대기열 진입 중 오류 발생", e);
            return ResponseEntity.internalServerError()
                    .body(new EnterResponse(EnterResponse.Status.ERROR, "서버 내부 오류", request.requestId(), null, null));
        }
    }

    @Operation(summary = "대기열 퇴장", description = "대기열에서 퇴장합니다")
    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@RequestBody LeaveRequest request) {
        try {
            admissionService.leave("movie", request.getMovieId(), 
                                 request.getSessionId(), request.getRequestId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("대기열 퇴장 중 오류 발생", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ✅ 새로 추가: 프론트엔드에서 요청하는 시스템 설정 API
    @Operation(summary = "시스템 설정 조회", description = "대기열 시스템 설정을 조회합니다")
    @GetMapping("/system/config")
    public ResponseEntity<Map<String, Object>> getSystemConfig() {
        try {
            var sessionInfo = sessionCalculator.getCalculationInfo();
            
            Map<String, Object> config = Map.of(
                "baseSessionsPerPod", sessionInfo.baseSessionsPerPod(),
                "waitTimePerPodSeconds", 10, // 파드당 대기 시간 (10초)
                "currentPodCount", sessionInfo.currentPodCount(),
                "maxTotalSessions", sessionInfo.calculatedMaxSessions(),
                "sessionTimeoutSeconds", sessionTimeoutSeconds, // 동적 값으로 변경!
                "dynamicScalingEnabled", sessionInfo.dynamicScalingEnabled(),
                "kubernetesAvailable", sessionInfo.kubernetesAvailable()
            );
            
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("❌ 시스템 설정 조회 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ✅ 새로 추가: 사용자 상태 확인 API
    @Operation(summary = "사용자 상태 확인", description = "사용자의 현재 대기열/활성 세션 상태를 확인합니다")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String movieId,
            @RequestParam String sessionId, 
            @RequestParam String requestId) {
        try {
            // 활성 세션에 있는지 확인
            if (admissionService.isUserInActiveSession("movie", movieId, sessionId, requestId)) {
                return ResponseEntity.ok(Map.of(
                    "status", "ACTIVE", 
                    "action", "REDIRECT_TO_SEATS"
                ));
            }
            
            // 대기열에 있는지 확인
            Long rank = admissionService.getUserRank("movie", movieId, sessionId, requestId);
            if (rank != null) {
                long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                return ResponseEntity.ok(Map.of(
                    "status", "WAITING", 
                    "rank", rank, 
                    "totalWaiting", totalWaiting
                ));
            }
            
            // 둘 다 없음
            return ResponseEntity.ok(Map.of(
                "status", "NOT_FOUND", 
                "action", "REDIRECT_TO_MOVIES"
            ));
            
        } catch (Exception e) {
            logger.error("❌ 사용자 상태 확인 실패", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}