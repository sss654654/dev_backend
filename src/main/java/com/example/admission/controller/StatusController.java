// src/main/java/com/example/admission/controller/StatusController.java - 사용자 상태 확인 API

package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final Logger logger = LoggerFactory.getLogger(StatusController.class);
    private final AdmissionService admissionService;

    public StatusController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    /**
     * 🎯 사용자 현재 상태 확인 (활성세션 vs 대기열)
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String requestId,
            @RequestParam String sessionId,
            @RequestParam String movieId) {
        
        try {
            String member = requestId + ":" + sessionId;
            
            // 1. 활성 세션에 있는지 확인
            boolean isActive = admissionService.isInActiveSession("movie", movieId, member);
            
            if (isActive) {
                logger.info("✅ STATUS CHECK: 사용자가 활성세션에 있음 - {}...", requestId.substring(0, 8));
                return ResponseEntity.ok(Map.of(
                    "status", "ACTIVE",
                    "message", "활성세션에 있습니다",
                    "action", "REDIRECT_TO_SEATS"
                ));
            }
            
            // 2. 대기열에 있는지 확인 및 순위 조회
            Long rank = admissionService.getUserRank("movie", movieId, member);
            
            if (rank != null) {
                long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                logger.info("📋 STATUS CHECK: 사용자가 대기열에 있음 - {}..., 순위: {}/{}", 
                           requestId.substring(0, 8), rank, totalWaiting);
                
                return ResponseEntity.ok(Map.of(
                    "status", "WAITING",
                    "message", "대기열에 있습니다",
                    "rank", rank,
                    "totalWaiting", totalWaiting,
                    "action", "STAY_IN_QUEUE"
                ));
            }
            
            // 3. 어디에도 없음 (세션 만료 등)
            logger.warn("⚠️ STATUS CHECK: 사용자를 찾을 수 없음 - {}...", requestId.substring(0, 8));
            return ResponseEntity.ok(Map.of(
                "status", "NOT_FOUND",
                "message", "세션이 만료되었거나 찾을 수 없습니다",
                "action", "REDIRECT_TO_MOVIES"
            ));
            
        } catch (Exception e) {
            logger.error("❌ STATUS CHECK: 상태 확인 실패 - requestId: {}...", 
                        requestId.substring(0, 8), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR",
                "message", "상태 확인 중 오류가 발생했습니다"
            ));
        }
    }

    /**
     * 🔍 영화별 대기열 현황 조회
     */
    @GetMapping("/movie/{movieId}")
    public ResponseEntity<Map<String, Object>> getMovieStatus(@PathVariable String movieId) {
        try {
            long activeCount = admissionService.getTotalActiveCount("movie", movieId);
            long waitingCount = admissionService.getTotalWaitingCount("movie", movieId);
            
            return ResponseEntity.ok(Map.of(
                "movieId", movieId,
                "activeCount", activeCount,
                "waitingCount", waitingCount,
                "timestamp", System.currentTimeMillis()
            ));
            
        } catch (Exception e) {
            logger.error("❌ 영화 상태 조회 실패 - movieId: {}", movieId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                "status", "ERROR",
                "message", "영화 상태 조회 실패"
            ));
        }
    }
}