// src/main/java/com/example/admission/controller/StatusController.java
package com.example.admission.controller;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final Logger logger = LoggerFactory.getLogger(StatusController.class);
    private final AdmissionService admissionService;

    public StatusController(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String requestId,
            @RequestParam String sessionId,
            @RequestParam String movieId) {
        
        String member = requestId + ":" + sessionId;
        
        if (admissionService.isInActiveSession("movie", movieId, member)) {
            return ResponseEntity.ok(Map.of("status", "ACTIVE", "action", "REDIRECT_TO_SEATS"));
        }
        
        Long rank = admissionService.getUserRank("movie", movieId, member);
        
        if (rank != null) {
            long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
            return ResponseEntity.ok(Map.of("status", "WAITING", "rank", rank, "totalWaiting", totalWaiting, "action", "STAY_IN_QUEUE"));
        }
        
        return ResponseEntity.ok(Map.of("status", "NOT_FOUND", "action", "REDIRECT_TO_MOVIES"));
    }
}