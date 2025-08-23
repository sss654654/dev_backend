package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admission")
public class AdmissionController {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionController.class);
    private final AdmissionService admissionService;

    public AdmissionController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/enter")
    public ResponseEntity<EnterResponse> enter(@RequestBody EnterRequest request) {
        // ★ 요청 파라미터 유효성 검사
        if (request.getMovieId() == null || request.getMovieId().isBlank()) {
            // ★ 수정된 EnterResponse 생성자에 맞게 null 추가
            return ResponseEntity.badRequest().body(new EnterResponse(EnterResponse.Status.FAILED, "movieId는 필수입니다.", null, null, null));
        }

        EnterResponse result = admissionService.tryEnter(
                "movie", // 타입은 'movie'로 고정
                request.getMovieId(),
                request.getSessionId(),
                request.getRequestId()
        );

        // ★ getStatus()를 사용하여 상태 확인
        if (result.getStatus() == EnterResponse.Status.QUEUED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        }
        
        return ResponseEntity.ok(result);
    }

    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@RequestBody EnterRequest request) {
        if (request.getMovieId() == null || request.getSessionId() == null || request.getRequestId() == null) {
            return ResponseEntity.badRequest().build();
        }
        
        admissionService.leave(
                "movie",
                request.getMovieId(),
                request.getSessionId(),
                request.getRequestId()
        );
        return ResponseEntity.ok().build();
    }
}