package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admission")
public class AdmissionController {

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
            // 즉시 입장 성공: 200 OK 응답
            return ResponseEntity.ok("즉시 입장 처리되었습니다. 예매 페이지로 이동합니다.");
        } else {
            // 대기열 등록: 202 Accepted 응답
            // 클라이언트는 이 응답을 받고 WebSocket으로 순위를 확인해야 함을 인지
            return ResponseEntity.status(HttpStatus.ACCEPTED).body("대기열에 등록되었습니다. 실시간 순위를 확인하세요.");
        }
    }
}