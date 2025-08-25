package com.example.admission.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔹 개선된 WebSocket 업데이트 서비스 - 통계 추적과 상세 로깅 포함
 */
@Service
public class WebSocketUpdateService {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketUpdateService.class);
    private final SimpMessagingTemplate template;
    
    // 🔹 통계 추적
    private final AtomicLong admitNotificationCount = new AtomicLong(0);
    private final AtomicLong rankUpdateCount = new AtomicLong(0);
    private final AtomicLong statsUpdateCount = new AtomicLong(0);
    private final AtomicLong timeoutNotificationCount = new AtomicLong(0);

    public WebSocketUpdateService(SimpMessagingTemplate template) {
        this.template = template;
        logger.info("🚀 WebSocketUpdateService 초기화 완료");
    }

    /**
     * 🔹 핵심 기능: 특정 사용자에게 입장이 허가되었음을 알립니다. (개인 메시지)
     */
    public void notifyAdmitted(String requestId) {
        try {
            String destination = "/topic/admit/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "ADMITTED",
                "message", "입장이 허가되었습니다. 예매를 진행해주세요.",
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            admitNotificationCount.incrementAndGet();
            
            // 🚨 핵심 로깅: 입장 알림이 정확히 전송되었는지 확인
            logger.info("🎬 WEBSOCKET: 입장 허가 알림 전송 완료 | destination: {} | requestId: {} | payload: {}", 
                       destination, requestId, payload);
                       
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 입장 허가 알림 전송 실패 - requestId: {}", requestId, e);
        }
    }

    /**
     * 🔹 특정 사용자에게 현재 대기 순번을 알려줍니다. (개인 메시지)
     */
    public void notifyRankUpdate(String requestId, long rank) {
        try {
            String destination = "/topic/rank/" + requestId;
            Map<String, Object> payload = Map.of(
                "rank", rank,
                "status", "WAITING",
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            rankUpdateCount.incrementAndGet();
            
            logger.debug("📊 WEBSOCKET: 순위 업데이트 전송 | destination: {} | rank: {} | requestId: {}", 
                        destination, rank, requestId);
                        
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 순위 업데이트 전송 실패 - requestId: {}, rank: {}", requestId, rank, e);
        }
    }

    /**
     * 🔹 특정 영화의 전체 대기열 상태를 모든 대기자에게 방송합니다. (공개 메시지)
     */
    public void broadcastQueueStats(String movieId, long totalWaiting) {
        try {
            String destination = String.format("/topic/stats/movie/%s", movieId);
            Map<String, Object> payload = Map.of(
                "movieId", movieId,
                "totalWaiting", totalWaiting,
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            statsUpdateCount.incrementAndGet();
            
            logger.debug("📈 WEBSOCKET: 대기열 통계 브로드캐스트 | destination: {} | totalWaiting: {} | movieId: {}", 
                        destination, totalWaiting, movieId);
                        
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 대기열 통계 브로드캐스트 실패 - movieId: {}, totalWaiting: {}", 
                        movieId, totalWaiting, e);
        }
    }

    /**
     * 🔹 세션 타임아웃으로 퇴장된 사용자에게 알림을 보냅니다.
     */
    public void notifyTimeout(String requestId) {
        try {
            String destination = "/topic/timeout/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "TIMEOUT",
                "message", "세션 유효 시간이 만료되어 자동으로 퇴장 처리되었습니다.",
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            timeoutNotificationCount.incrementAndGet();
            
            logger.warn("⏰ WEBSOCKET: 타임아웃 알림 전송 | destination: {} | requestId: {}", 
                       destination, requestId);
                       
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 타임아웃 알림 전송 실패 - requestId: {}", requestId, e);
        }
    }

    /**
     * 🔹 새로운 기능: 대기열 진입 확인 메시지 (사용자가 대기열에 등록되었을 때)
     */
    public void notifyQueueJoined(String requestId, long position, long totalWaiting) {
        try {
            String destination = "/topic/queue/joined/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "QUEUED",
                "message", "대기열에 등록되었습니다.",
                "position", position,
                "totalWaiting", totalWaiting,
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            
            logger.info("🚶‍♂️ WEBSOCKET: 대기열 진입 확인 | destination: {} | position: {} | totalWaiting: {} | requestId: {}", 
                       destination, position, totalWaiting, requestId);
                       
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 대기열 진입 확인 전송 실패 - requestId: {}", requestId, e);
        }
    }

    /**
     * 🔹 새로운 기능: 에러 메시지 전송 (시스템 오류 시)
     */
    public void notifyError(String requestId, String errorMessage) {
        try {
            String destination = "/topic/error/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "ERROR",
                "message", errorMessage,
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            
            logger.error("🚨 WEBSOCKET: 에러 알림 전송 | destination: {} | message: {} | requestId: {}", 
                        destination, errorMessage, requestId);
                        
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET: 에러 알림 전송 실패 - requestId: {}, message: {}", requestId, errorMessage, e);
        }
    }

    /**
     * 🔹 WebSocket 서비스 통계 조회 (모니터링용)
     */
    public Map<String, Object> getWebSocketStats() {
        return Map.of(
            "admitNotifications", admitNotificationCount.get(),
            "rankUpdates", rankUpdateCount.get(),
            "statsUpdates", statsUpdateCount.get(),
            "timeoutNotifications", timeoutNotificationCount.get(),
            "totalMessages", admitNotificationCount.get() + rankUpdateCount.get() + 
                           statsUpdateCount.get() + timeoutNotificationCount.get()
        );
    }

    /**
     * 🔹 주기적 통계 로깅 (필요시 스케줄러에서 호출)
     */
    public void logStatistics() {
        logger.info("📊 WEBSOCKET 통계 - 입장알림: {}, 순위업데이트: {}, 통계방송: {}, 타임아웃: {}, 총계: {}", 
                   admitNotificationCount.get(), rankUpdateCount.get(), statsUpdateCount.get(),
                   timeoutNotificationCount.get(), 
                   admitNotificationCount.get() + rankUpdateCount.get() + statsUpdateCount.get() + timeoutNotificationCount.get());
    }
}