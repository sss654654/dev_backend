package com.example.couponmanagement.controller;

import com.example.couponmanagement.domain.ClaimStatus;
import com.example.couponmanagement.dto.RequestCoupon;
import com.example.couponmanagement.dto.RequestCouponCommand;
import com.example.couponmanagement.service.CouponService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;


@RestController
@RequestMapping("/api/coupons")
//@CrossOrigin(origins = "*", exposedHeaders = { "Location", "location" })
@Tag(name = "Coupon Management", description = "쿠폰 관리 API")
public class CouponController {
    private final CouponService couponService;

    @Autowired
    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping("/request")
    public ResponseEntity<Map<String,Object>> claimAny(@RequestBody RequestCoupon req) {
        String requestId = couponService.acceptAny(req.getUserId());
//        String s3Url = "https://your-s3-bucket-name.s3.ap-northeast-2.amazonaws.com/wait.html?requestId=" + requestId;
//
//        // S3 URL로 직접 리다이렉트하라고 브라우저에 명령
//        return ResponseEntity
//                .status(303)
//                .location(URI.create(s3Url))
//                .build();
        String waitPath = "/wait.html?requestId=" + requestId;

        // "redirectUrl" 키에 생성된 URL을 담아 JSON 형태로 반환
        return ResponseEntity
                .accepted()                               // 202 Accepted (비동기 큐에 올림)
                .header(HttpHeaders.LOCATION, waitPath)   // 선택: Location도 실어줌
                .body(Map.of(
                        "requestId", requestId,
                        "waitUrl", waitPath
                ));
    }

}
