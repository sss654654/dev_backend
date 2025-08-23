package com.example.couponmanagement.controller;

import com.example.admission.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private final AdmissionService admissionService;

    public CouponController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @PostMapping("/use")
    public ResponseEntity<Map<String, Object>> useCoupon(@RequestBody Map<String, String> payload) {
        String couponCode = payload.get("couponCode");
        String userId = payload.get("userId");

        if (couponCode == null || userId == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "쿠폰 코드와 사용자 ID는 필수입니다."));
        }

        // --- 쿠폰 사용 로직 시뮬레이션 ---
        boolean isCouponValid = "DISCOUNT2000".equals(couponCode);
        
        if (isCouponValid) {
            // ★ 쿠폰 사용 로직이 성공했다고 가정
            // 대기열 관련 로직은 여기에서 직접 처리하지 않습니다.
            // 클라이언트는 쿠폰 사용 후, 별도로 대기열 입장 API를 호출해야 합니다.
            return ResponseEntity.ok(Map.of("success", true, "message", "쿠폰이 성공적으로 적용되었습니다."));
        } else {
            return ResponseEntity.status(400).body(Map.of("success", false, "message", "유효하지 않은 쿠폰입니다."));
        }
    }
}