package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admission")
public class AdmissionController {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionController.class);
    private final AdmissionService admissionService;

    public AdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/enter")
    public ResponseEntity<String> enter(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body("sessionId가 필요합니다.");
        }
        AdmissionService.AdmissionResult result = admissionService.tryEnter(sessionId);
        if (result == AdmissionService.AdmissionResult.SUCCESS) {
            return ResponseEntity.ok("즉시 입장 처리되었습니다. 예매 페이지로 이동합니다.");
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body("대기열에 등록되었습니다. 실시간 순위를 확인하세요.");
        }
    }

    @PostMapping("/leave")
    public ResponseEntity<String> leave(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body("sessionId가 필요합니다.");
        }
        admissionService.leave(sessionId);
        logger.info("ACTIVE SESSION: {} 님이 세션에서 나갔습니다. (빈자리 발생)", sessionId);
        return ResponseEntity.ok(sessionId + " 님이 세션에서 나갔습니다.");
    }

    // ✅ [역할 1] 시스템 전체 현황 모니터링용 API
    @GetMapping("/status")
    public ResponseEntity<Map<String, Long>> getSystemStatus() {
        long activeCount = admissionService.getActiveSessionCount();
        long waitingCount = admissionService.getTotalWaitingCount();
        return ResponseEntity.ok(Map.of("activeSessions", activeCount, "waitingQueue", waitingCount));
    }

    // ✅ [역할 2] 개별 사용자의 상태 확인 및 폴링용 API
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<Map<String, Object>> getUserStatus(@PathVariable String sessionId) {
        Map<String, Object> statusInfo = admissionService.getUserStatusAndRank(sessionId);
        if (statusInfo == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(statusInfo);
    }
}