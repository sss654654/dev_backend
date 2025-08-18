package com.example.queue.controller;

import com.example.queue.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@CrossOrigin(
    origins = "*", // 모든 출처 허용
    methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS} // OPTIONS 메서드를 명시적으로 추가
)
@Tag(name = "Queue Management", description = "대기열 관리 API")
public class QueueController {

    private final QueueService queueService;
    private final SimpMessagingTemplate messagingTemplate;

    public QueueController(QueueService queueService, SimpMessagingTemplate messagingTemplate) {
        this.queueService = queueService;
        this.messagingTemplate = messagingTemplate;
    }

    @Operation(summary = "대기열 진입", description = "특정 세션을 대기열에 추가합니다.")
    @PostMapping("/api/queue/enter")
    public ResponseEntity<String> enterQueue(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest().body("sessionId가 필요합니다.");
        }
        queueService.addUserToQueue(sessionId);
        Long totalWaiting = queueService.getTotalWaitingCount();
        messagingTemplate.convertAndSend("/topic/queue/size", totalWaiting);
        return ResponseEntity.ok(sessionId + " 님이 대기열에 추가되었습니다.");
    }

    @Operation(summary = "총 대기자 수 조회", description = "현재 대기열의 총 인원을 조회합니다.")
    @GetMapping("/api/queue/size")
    public ResponseEntity<Long> getQueueSize() {
        return ResponseEntity.ok(queueService.getTotalWaitingCount());
    }

    @MessageMapping("/queue/rank")
    public void getRank(Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        if (sessionId != null) {
            Long rank = queueService.getUserRank(sessionId);
            String destination = "/topic/queue/rank/" + sessionId;
            messagingTemplate.convertAndSend(destination, rank != null ? rank + 1 : "N/A");
        }
    }
}
