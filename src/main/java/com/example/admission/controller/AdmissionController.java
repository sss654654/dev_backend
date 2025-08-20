package com.example.admission.controller;

import com.example.admission.dto.EnterRequest;
import com.example.admission.dto.EnterResponse;
import com.example.admission.dto.RankResponse;
import com.example.admission.dto.StatusResponse;
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
        if (request.getType() == null || request.getId() == null || request.getSessionId() == null) {
            return ResponseEntity.badRequest().body(new EnterResponse(EnterResponse.Status.FAILED, "type, id, sessionId는 필수입니다.", null, null));
        }

        EnterResponse result = admissionService.tryEnter(
                request.getType(),
                request.getId(),
                request.getSessionId(),
                request.getRequestId()
        );

        if (result.getStatus() == EnterResponse.Status.QUEUED) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        }
        
        return ResponseEntity.ok(result);
    }

    @PostMapping("/leave")
    public ResponseEntity<Void> leave(@RequestBody EnterRequest request) {
        if (request.getType() == null || request.getId() == null || request.getSessionId() == null || request.getRequestId() == null) {
            return ResponseEntity.badRequest().build();
        }
        
        // 수정된 leave 메서드 호출
        admissionService.leave(
                request.getType(),
                request.getId(),
                request.getSessionId(),
                request.getRequestId()
        );
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{type}/{id}/status")
    public ResponseEntity<StatusResponse> getStatus(@PathVariable String type, @PathVariable String id) {
        long activeCount = admissionService.getActiveUserCount(type, id);
        long waitingCount = admissionService.getTotalWaitingCount(type, id);
        
        StatusResponse response = new StatusResponse(type, id, activeCount, waitingCount);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{type}/{id}/rank")
    public ResponseEntity<RankResponse> getRank(
            @PathVariable String type,
            @PathVariable String id,
            @RequestParam String requestId) {
                
        Long rank = admissionService.getUserRank(type, id, requestId);
        
        if (rank != null) {
            return ResponseEntity.ok(new RankResponse(requestId, rank + 1));
        }
        
        return ResponseEntity.notFound().build();
    }
}