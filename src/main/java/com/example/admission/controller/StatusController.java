// src/main/java/com/example/admission/controller/StatusController.java
package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final AdmissionService admissionService;

    public StatusController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String requestId,
            @RequestParam String sessionId,
            @RequestParam String movieId) {
        
        // isUserInActiveSession 메서드 시그니처에 맞게 호출
        if (admissionService.isUserInActiveSession("movie", movieId, sessionId, requestId)) {
            return ResponseEntity.ok(Map.of("status", "ACTIVE", "action", "REDIRECT_TO_SEATS"));
        }
        
        // getUserRank 메서드 시그니처에 맞게 호출
        Long rank = admissionService.getUserRank("movie", movieId, sessionId, requestId);
        
        if (rank != null) {
            long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
            return ResponseEntity.ok(Map.of("status", "WAITING", "rank", rank, "totalWaiting", totalWaiting));
        }
        
        return ResponseEntity.ok(Map.of("status", "NOT_FOUND", "action", "REDIRECT_TO_MOVIES"));
    }
}