package com.example.couponmanagement.controller;

import com.example.admission.service.AdmissionService;
import com.example.couponmanagement.dto.RequestCoupon;
import com.example.couponmanagement.service.CouponService;
import com.example.session.service.SessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;


@RestController
@RequestMapping("/api/coupons")
//@CrossOrigin(origins = "*", exposedHeaders = { "Location", "location" })
@Tag(name = "Coupon Management", description = "쿠폰 관리 API")
public class CouponController {
    private final CouponService couponService;
    private final SessionService sessionService;
    private final AdmissionService admissionService;

    @Autowired
    public CouponController(CouponService couponService,AdmissionService admissionService,SessionService sessionService) {
        this.couponService = couponService;
        this.admissionService = admissionService;
        this.sessionService = sessionService;
    }

    @PostMapping("/request")
    public ResponseEntity claimAny(@RequestBody RequestCoupon req,
                                                       HttpServletRequest request) {
        String requestId = couponService.acceptAny(req.getRequestId());
//        String s3Url = "https://your-s3-bucket-name.s3.ap-northeast-2.amazonaws.com/wait.html?requestId=" + requestId;
//
//        // S3 URL로 직접 리다이렉트하라고 브라우저에 명령
//        return ResponseEntity
//                .status(303)
//                .location(URI.create(s3Url))
//                .build();
        String waitPath = "/wait.html?requestId=" + requestId;


        String sessionId = sessionService.readSessionIdFromCookie(request)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "NO_SESSION_COOKIE"));


        if (!sessionService.isSessionValid(sessionId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(419), "SESSION_EXPIRED");
        }
        AdmissionService.AdmissionResult result = admissionService.tryEnter(sessionId);

        if (result == AdmissionService.AdmissionResult.SUCCESS) {
            return ResponseEntity.ok("즉시 입장 처리되었습니다. 예매 페이지로 이동합니다.");
        } else {
            //QUEUED
             return ResponseEntity
                    .accepted()                               // 202 Accepted (비동기 큐에 올림)
                    .header(HttpHeaders.LOCATION, waitPath)   // 선택: Location도 실어줌
                    .body(Map.of(
                            "myseq", requestId,
                            "waitUrl", waitPath
                    ));
        }


    }

}
