package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
import com.example.admission.dto.LeaveRequest;
import com.example.admission.dto.QueueStatusResponse;
import com.example.admission.service.AdmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admission")
@Tag(name = "Admission API", description = "대기열 관리 API")
public class AdmissionController {
    
    private static final Logger logger = LoggerFactory.getLogger(AdmissionController.class);
    private final AdmissionService admissionService;
    
    public AdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @Operation(summary = "대기열 진입", description = "영화 예매 대기열에 진입합니다")
    @PostMapping("/enter")
    public ResponseEntity<EnterResponse> enter(@RequestBody EnterRequest request) {
        logger.info("🎬 대기열 진입 요청 - movieId: {}, requestId: {}...", 
                   request.movieId(), request.requestId().substring(0, 8));
        
        try {
            EnterResponse response = admissionService.enter("movie", request.movieId(), 
                                                          request.sessionId(), request.requestId());
            
            if (response.status() == EnterResponse.Status.SUCCESS) {
                logger.info("✅ 즉시 입장 - requestId: {}...", request.requestId().substring(0, 8));
                return ResponseEntity.ok(response);
            } else {
                logger.info("📋 대기열 등록 - requestId: {}..., 순위: {}", 
                           request.requestId().substring(0, 8), response.myRank());
                return ResponseEntity.accepted().body(response);
            }
            
        } catch (Exception e) {
            logger.error("❌ 대기열 진입 중 오류 발생", e);
            return ResponseEntity.internalServerError()
                    .body(new EnterResponse(EnterResponse.Status.ERROR, 
                                          "서버 내부 오류가 발생했습니다.", 
                                          request.requestId(), null, null));
        }
    }

    @Operation(summary = "대기열 퇴장", description = "대기열에서 퇴장합니다")
   // AdmissionController.java의 leave 메서드 수정:

@PostMapping("/leave")
public ResponseEntity<Void> leave(@RequestBody LeaveRequest request) {
    logger.info("대기열 퇴장 요청 - movieId: {}, requestId: {}...", 
               request.getMovieId(), request.getRequestId().substring(0, 8));
    
    try {
        admissionService.leave("movie", request.getMovieId(), 
                             request.getSessionId(), request.getRequestId());
        return ResponseEntity.ok().build();
    } catch (Exception e) {
        logger.error("대기열 퇴장 중 오류 발생", e);
        return ResponseEntity.internalServerError().build();
    }
}
    @Operation(summary = "대기열 상태 조회", description = "현재 대기 순위와 총 대기자 수를 조회합니다")
    @GetMapping("/status/{movieId}/{requestId}")
    public ResponseEntity<QueueStatusResponse> getQueueStatus(
            @PathVariable String movieId,
            @PathVariable String requestId,
            @RequestParam String sessionId) {
        
        logger.info("📊 대기열 상태 조회 - movieId: {}, requestId: {}...", 
                   movieId, requestId.substring(0, 8));
        
        try {
            // 현재 대기 순위 조회
            Long rank = admissionService.getUserWaitingRank("movie", movieId, sessionId, requestId);
            
            if (rank == null) {
                // 대기열에 없으면 활성 세션인지 확인
                boolean isActive = admissionService.isUserInActiveSession("movie", movieId, sessionId, requestId);
                if (isActive) {
                    return ResponseEntity.ok(QueueStatusResponse.admitted());
                } else {
                    return ResponseEntity.notFound().build();
                }
            }
            
            // 총 대기자 수 조회
            long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
            
            QueueStatusResponse response = QueueStatusResponse.waiting(rank + 1, totalWaiting);
            
            logger.info("📊 상태 조회 결과 - requestId: {}..., 순위: {}/{}", 
                       requestId.substring(0, 8), rank + 1, totalWaiting);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("❌ 대기열 상태 조회 중 오류 발생", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}