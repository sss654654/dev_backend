package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
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
    public ResponseEntity<String> enter(@RequestBody EnterRequest request) {
        // 변경점: requestId 유효성 검사 추가
        if (request.getSessionId() == null || request.getMovieId() == null || request.getRequestId() == null) {
            return ResponseEntity.badRequest().body("sessionId, movieId, requestId는 필수입니다.");
        }

        // 변경점: Service 호출 시 requestId 전달
        AdmissionService.AdmissionResult result = admissionService.tryEnter(
                request.getSessionId(),
                request.getRequestId(),
                request.getMovieId()
        );

        if (result == AdmissionService.AdmissionResult.SUCCESS) {
            return ResponseEntity.ok("즉시 입장 처리되었습니다.");
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body("대기열에 등록되었습니다.");
        }
    }

    @PostMapping("/leave")
    public ResponseEntity<String> leave(@RequestBody EnterRequest request) {
        // 변경점: requestId 유효성 검사 추가 (leave에서는 sessionId와 movieId만으로도 충분할 수 있으나 일관성을 위해 추가)
        if (request.getSessionId() == null || request.getMovieId() == null) {
            return ResponseEntity.badRequest().body("sessionId, movieId는 필수입니다.");
        }

        // 변경점: Service 호출 시 requestId 전달 (로깅/추적용)
        admissionService.leave(
                request.getSessionId(),
                request.getRequestId(),
                request.getMovieId()
        );
        return ResponseEntity.ok(request.getSessionId() + " 님이 세션에서 나갔습니다.");
    }

    @GetMapping("/rank")
    public ResponseEntity<Long> getRank(@RequestParam String sessionId,
                                        @RequestParam String movieId) {
        Long rank = admissionService.getUserRank(sessionId, movieId);
        if (rank != null) {
            return ResponseEntity.ok(rank + 1);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Long>> getStatus(@RequestParam String movieId) {
        long activeCount = admissionService.getActiveSessionCount(movieId);
        long waitingCount = admissionService.getTotalWaitingCount(movieId);
        return ResponseEntity.ok(Map.of("activeSessions", activeCount, "waitingQueue", waitingCount));
    }
}