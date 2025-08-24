package com.example.admission.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class WebSocketUpdateService {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketUpdateService.class);
    private final SimpMessagingTemplate template;

    public WebSocketUpdateService(SimpMessagingTemplate template) {
        this.template = template;
    }

    /**
     * 특정 사용자에게 입장이 허가되었음을 알립니다. (개인 메시지)
     * @param requestId 대상 사용자의 요청 ID
     */
    public void notifyAdmitted(String requestId) {
        String destination = "/topic/admit/" + requestId;
        Map<String, Object> payload = Map.of(
            "status", "ADMITTED",
            "message", "입장이 허가되었습니다. 예매를 진행해주세요."
        );
        template.convertAndSend(destination, payload);
        logger.info("==> WebSocket ADMIT to {}: {}", destination, payload);
    }

    /**
     * 특정 사용자에게 현재 대기 순번을 알려줍니다. (개인 메시지)
     * @param requestId 대상 사용자의 요청 ID
     * @param rank 현재 순위
     */
    public void notifyRankUpdate(String requestId, long rank) {
        String destination = "/topic/rank/" + requestId;
        Map<String, Object> payload = Map.of("rank", rank);
        template.convertAndSend(destination, payload);
        logger.info("==> WebSocket RANK to {}: {}", destination, payload);
    }

    /**
     * 특정 영화의 전체 대기열 상태를 모든 대기자에게 방송합니다. (공개 메시지)
     * @param movieId 영화 ID
     * @param totalWaiting 총 대기자 수
     */
    public void broadcastQueueStats(String movieId, long totalWaiting) {
        String destination = String.format("/topic/stats/movie/%s", movieId);
        Map<String, Object> payload = Map.of(
            "totalWaiting", totalWaiting,
            "ts", System.currentTimeMillis()
        );
        template.convertAndSend(destination, payload);
        logger.info("==> WebSocket STATS to {}: {}", destination, payload);
    }

    /**
     * ★★★ 새로 추가: 세션 타임아웃으로 퇴장된 사용자에게 알림을 보냅니다. ★★★
     * @param requestId 사용자의 고유 요청 ID
     */
    public void notifyTimeout(String requestId) {
        String destination = "/topic/timeout/" + requestId;
        Map<String, String> payload = Map.of(
                "status", "TIMEOUT",
                "message", "세션 유효 시간이 만료되어 자동으로 퇴장 처리되었습니다."
        );
        template.convertAndSend(destination, payload);
        logger.warn("==> WebSocket TIMEOUT to {}: {}", destination, payload);
    }
}