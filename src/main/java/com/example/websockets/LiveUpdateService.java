package com.example.websockets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class LiveUpdateService {
    private static final Logger logger = LoggerFactory.getLogger(LiveUpdateService.class);
    private final SimpMessagingTemplate messagingTemplate;

    public LiveUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 대기 중인 사용자에게 현재 순위를 알려줍니다.
     * @param requestId 사용자의 고유 요청 ID
     * @param position 현재 대기 순번 (1부터 시작)
     */
    public void notifyRankUpdate(String requestId, long position) {
        String destination = "/topic/wait/" + requestId;
        Map<String, Object> payload = Map.of(
                "position", position,
                "status", "WAITING"
        );
        messagingTemplate.convertAndSend(destination, payload);
    }

    /**
     * 입장이 허가된 사용자에게 최종 알림을 보냅니다.
     * @param requestId 사용자의 고유 요청 ID
     * @param movieId 입장한 영화 ID
     * @param sessionId 사용자의 세션 ID
     */
    public void notifyAdmitted(String requestId, String movieId, String sessionId) {
        String destination = "/topic/wait/" + requestId;
        Map<String, String> payload = Map.of(
                "status", "ADMITTED",
                "message", "입장이 허가되었습니다. 예매를 진행해주세요.",
                "movieId", movieId,
                "ticketingUrl", "/ticket/" + movieId + "?sessionId=" + sessionId // 실제 예매 페이지 URL
        );
        messagingTemplate.convertAndSend(destination, payload);
        logger.info("==> WebSocket to {}: 입장 알림 전송 완료 <==", destination);
    }

    // ▼▼▼▼▼▼▼▼▼▼ 아래 메서드를 추가해주세요 ▼▼▼▼▼▼▼▼▼▼
    /**
     * 세션 타임아웃으로 퇴장된 사용자에게 알림을 보냅니다.
     * @param requestId 사용자의 고유 요청 ID
     */
    public void notifyTimeout(String requestId) {
        String destination = "/topic/wait/" + requestId;
        Map<String, String> payload = Map.of(
                "status", "TIMEOUT",
                "message", "세션 유효 시간이 만료되어 자동으로 퇴장 처리되었습니다."
        );
        messagingTemplate.convertAndSend(destination, payload);
        logger.warn("==> WebSocket to {}: 타임아웃 알림 전송 완료 <==", destination);
    }
}