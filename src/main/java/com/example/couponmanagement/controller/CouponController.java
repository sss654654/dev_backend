package com.example.couponmanagement.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
import com.example.admission.service.AdmissionService;
import com.example.session.service.SessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/coupons")
@Tag(name = "Coupon Management", description = "쿠폰 관리 API")
public class CouponController {
    private final AdmissionService admissionService;
    private final SessionService sessionService;
    
    public CouponController(AdmissionService admissionService, SessionService sessionService) {
        this.admissionService = admissionService;
        this.sessionService = sessionService;
    }

    @PostMapping("/request")
    public ResponseEntity<?> claimAny(@RequestBody EnterRequest req, HttpServletRequest request) {
        
        // 쿠키 기반으로 sessionId를 가져오고 유효성 검증
        String sessionId = sessionService.requireValidSessionOrThrow(request);

        // 범용 tryEnter 메서드 호출
        // 쿠폰은 종류가 하나이므로 id를 "global"과 같은 고정값으로 사용
        EnterResponse result = admissionService.tryEnter("coupon", "global", sessionId, req.getRequestId());

        if (result.getStatus() == EnterResponse.Status.SUCCESS) {
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "쿠폰이 즉시 발급되었습니다."));
        } else if (result.getStatus() == EnterResponse.Status.QUEUED) {
             return ResponseEntity
                    .accepted()
                    .header(HttpHeaders.LOCATION, result.getWaitUrl())
                    .body(Map.of(
                            "status", "QUEUED",
                            "waitUrl", result.getWaitUrl(),
                            "requestId", result.getRequestId()
                    ));
        } else {
            return ResponseEntity.badRequest().body(Map.of("status", "FAILED", "message", result.getMessage()));
        }
    }
}