package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
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

    /**
     * 🔹 대기열 진입 API
     */
    @PostMapping("/enter")
    public ResponseEntity<EnterResponse> enter(@RequestBody EnterRequest request) {
        // 요청 파라미터 유효성 검사
        if (request.getMovieId() == null || request.getMovieId().isBlank()) {
            return ResponseEntity.badRequest().body(new EnterResponse(
                EnterResponse.Status.FAILED, "movieId는 필수입니다.", null, null, null));
        }

        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            return ResponseEntity.badRequest().body(new EnterResponse(
                EnterResponse.Status.FAILED, "sessionId는 필수입니다.", null, null, null));
        }

        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            return ResponseEntity.badRequest().body(new EnterResponse(
                EnterResponse.Status.FAILED, "requestId는 필수입니다.", null, null, null));
        }

        logger.info("🎬 대기열 진입 요청 - movieId: {}, requestId: {}", 
                   request.getMovieId(), request.getRequestId().substring(0, 8) + "...");

        // ✅ 올바른 메서드명: enter 사용
        EnterResponse result = admissionService.enter(
                "movie", // 타입은 'movie'로 고정
                request.getMovieId(),
                request.getSessionId(),
                request.getRequestId()
        );

        // 응답 상태에 따른 HTTP 상태 코드 반환
        if (result.getStatus() == EnterResponse.Status.QUEUED) {
            logger.info("📋 대기열 등록 - requestId: {}, 순위: {}", 
                       request.getRequestId().substring(0, 8) + "...", result.getMyRank());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        } else if (result.getStatus() == EnterResponse.Status.SUCCESS) {
            logger.info("✅ 즉시 입장 - requestId: {}", 
                       request.getRequestId().substring(0, 8) + "...");
            return ResponseEntity.ok(result);
        } else {
            logger.error("❌ 입장 실패 - requestId: {}, 메시지: {}", 
                        request.getRequestId().substring(0, 8) + "...", result.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 🔹 대기열 퇴장 API
     */
    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@RequestBody EnterRequest request) {
        if (request.getMovieId() == null || request.getSessionId() == null || request.getRequestId() == null) {
            logger.warn("⚠️ 퇴장 요청 실패 - 필수 파라미터 누락");
            return ResponseEntity.badRequest().build();
        }
        
        logger.info("🚪 퇴장 처리 - movieId: {}, requestId: {}", 
                   request.getMovieId(), request.getRequestId().substring(0, 8) + "...");
        
        admissionService.leave(
                "movie",
                request.getMovieId(),
                request.getSessionId(),
                request.getRequestId()
        );
        
        logger.info("✅ 퇴장 완료 - requestId: {}", 
                   request.getRequestId().substring(0, 8) + "...");
        
        return ResponseEntity.ok().build();
    }

    /**
     * 🔹 새로운 기능: 현재 순위 조회 API (선택사항)
     */
    @GetMapping("/position")
    public ResponseEntity<Map<String, Object>> getCurrentPosition(
            @RequestParam String movieId,
            @RequestParam String requestId) {
        
        Map<String, Long> userRanks = admissionService.getAllUserRanks("movie", movieId);
        Long myRank = userRanks.get(requestId);
        long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
        
        Map<String, Object> response = Map.of(
            "movieId", movieId,
            "requestId", requestId,
            "currentRank", myRank != null ? myRank : -1,
            "totalWaiting", totalWaiting,
            "status", myRank != null ? "WAITING" : "NOT_FOUND"
        );
        
        return ResponseEntity.ok(response);
    }
}