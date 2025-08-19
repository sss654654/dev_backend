package com.example.couponmanagement.ws;


import com.example.admission.NotificationConsumer;
import com.example.couponmanagement.dto.WaitUpdateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class LiveUpdatePublisher {
    private static final Logger logger = LoggerFactory.getLogger(LiveUpdatePublisher.class);

    private final SimpMessagingTemplate template;
    public LiveUpdatePublisher(SimpMessagingTemplate template) { this.template = template; }

    public void broadcastStats(long headSeq, long totalWaiting) {
        Map<String, Object> payload = Map.of(
                "headSeq", headSeq,               // 처리된(입장한) 마지막 시퀀스
                "totalWaiting", totalWaiting,     // 현재 큐 길이
                "ts", System.currentTimeMillis()
        );
        template.convertAndSend("/topic/stats/", payload);
    }

    /** 1:1 알림: 해당 요청 한 명에게만 (개별 토픽 이용) */
    public void notifyAdmitted(String requestId) {
        Map<String, Object> payload = Map.of(
                "requestId", requestId,
                "status", "ADMITTED",
                "message", "입장 허가되었습니다. 다음 단계로 진행하세요."
        );
        String destination = "/topic/admit/" + requestId;
        template.convertAndSend(destination, payload);
        logger.info("==> WebSocket to {}: 입장 알림 전송 완료 <==", destination);
    }
}