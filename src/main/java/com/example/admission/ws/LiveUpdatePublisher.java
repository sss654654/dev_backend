package com.example.admission.ws;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

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


    public void broadcastStats(String type, String id, long headSeq, long totalWaiting) {
        // ★ 1. 어떤 큐에 대한 정보인지 구분하기 위해 동적 토픽 주소를 생성합니다.
        String destination = String.format("/topic/stats/%s/%s", type, id);

        Map<String, Object> payload = Map.of(
                "headSeq", headSeq,
                "totalWaiting", totalWaiting,
                "ts", System.currentTimeMillis()
        );

        // ★ 2. 해당 큐를 구독하는 클라이언트에게만 메시지를 보냅니다.
        template.convertAndSend(destination, payload);
        logger.info("==> WebSocket Broadcast to {}: headSeq={}, totalWaiting={}", destination, headSeq, totalWaiting);
    }

}