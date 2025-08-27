// src/main/java/com/example/admission/ws/WebSocketUpdateService.java
package com.example.admission.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class WebSocketUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketUpdateService.class);
    
    private final SimpMessagingTemplate template;
    private final AtomicLong admissionNotificationCount = new AtomicLong();
    private final AtomicLong rankUpdateCount = new AtomicLong();
    private final AtomicLong statsUpdateCount = new AtomicLong();
    private final AtomicLong timeoutNotificationCount = new AtomicLong();

    public WebSocketUpdateService(SimpMessagingTemplate template) {
        this.template = template;
    }

    /**
     * ✅ 핵심 수정: 입장 허가 시 좌석 선택으로 이동하도록 강화된 알림
     */
    public void notifyAdmission(String requestId, String movieId) {
        try {
            String destination = "/topic/admission/" + requestId;
            
            // ✅ 중요: 좌석 페이지로 이동하기 위한 상세 정보 포함
            Map<String, Object> payload = Map.of(
                "status", "ADMITTED",
                "action", "REDIRECT_TO_SEATS",          // 액션 명시
                "requestId", requestId,
                "movieId", movieId,
                "message", "🎉 입장이 허가되었습니다! 좌석 선택 페이지로 이동합니다.",
                "redirectUrl", "/seats",                // 이동할 URL
                "timestamp", System.currentTimeMillis()
            );
            
            logger.info("🎬 WEBSOCKET [입장 허가]: destination: {} | requestId: {}... | movieId: {}", 
                       destination, requestId.substring(0, 8), movieId);
            
            // 메시지 전송
            template.convertAndSend(destination, payload);
            admissionNotificationCount.incrementAndGet();
            
            logger.info("✅ WEBSOCKET [전송 완료]: 입장 허가 알림 전송 성공 (총 {}번째)", 
                       admissionNotificationCount.get());
                    
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET [ERROR]: 입장 허가 알림 전송 실패 - requestId: {}..., movieId: {}", 
                        requestId.substring(0, 8), movieId, e);
        }
    }

    /**
     * ✅ 수정: 순위 업데이트 로직 강화
     */
    public void notifyRankUpdate(String requestId, String status, long rank, long totalWaiting) {
        try {
            String destination = "/topic/rank/" + requestId;
            
            Map<String, Object> payload = Map.of(
                "status", status,
                "rank", rank,
                "totalWaiting", totalWaiting,
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId,
                "message", String.format("현재 %d번째 순서입니다. (전체 %d명 대기)", rank, totalWaiting)
            );
            
            template.convertAndSend(destination, payload);
            rankUpdateCount.incrementAndGet();
            
            logger.debug("📊 WEBSOCKET [순위 업데이트]: requestId: {}... | rank: {}/{}", 
                        requestId.substring(0, 8), rank, totalWaiting);
                        
        } catch (Exception e) {
            logger.error("❌ 순위 업데이트 전송 실패 - requestId: {}..., rank: {}", 
                        requestId.substring(0, 8), rank, e);
        }
    }

    /**
     * 📈 대기열 전체 통계를 해당 영화의 모든 사용자에게 브로드캐스트
     */
    public void broadcastQueueStats(String movieId, long totalWaiting) {
        try {
            String destination = "/topic/stats/movie/" + movieId;
            Map<String, Object> payload = Map.of(
                "movieId", movieId,
                "totalWaiting", totalWaiting,
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            statsUpdateCount.incrementAndGet();
            
            logger.debug("📈 WEBSOCKET [통계 브로드캐스트]: movieId: {} | totalWaiting: {}", 
                        movieId, totalWaiting);
                        
        } catch (Exception e) {
            logger.error("❌ 대기열 통계 브로드캐스트 실패 - movieId: {}, totalWaiting: {}", 
                        movieId, totalWaiting, e);
        }
    }

    /**
     * ⏰ 세션 타임아웃으로 퇴장된 사용자에게 알림
     */
    public void notifyTimeout(String requestId) {
        try {
            String destination = "/topic/timeout/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "TIMEOUT",
                "action", "REDIRECT_TO_MOVIES",
                "message", "세션 유효 시간이 만료되어 자동으로 퇴장 처리되었습니다.",
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            timeoutNotificationCount.incrementAndGet();
            
            logger.warn("⏰ WEBSOCKET [타임아웃]: requestId: {}...", requestId.substring(0, 8));
                       
        } catch (Exception e) {
            logger.error("❌ 타임아웃 알림 전송 실패 - requestId: {}...", requestId.substring(0, 8), e);
        }
    }

    /**
     * ✅ 새로 추가: 대기열 진입 확인 메시지
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
            
            logger.info("📋 WEBSOCKET [대기열 진입]: requestId: {}... | position: {}/{}", 
                       requestId.substring(0, 8), position, totalWaiting);
                       
        } catch (Exception e) {
            logger.error("❌ 대기열 진입 확인 전송 실패 - requestId: {}...", requestId.substring(0, 8), e);
        }
    }

    /**
     * 📊 WebSocket 통계 조회
     */
    public Map<String, Object> getWebSocketStats() {
        return Map.of(
            "admissionNotifications", admissionNotificationCount.get(),
            "rankUpdates", rankUpdateCount.get(),
            "statsUpdates", statsUpdateCount.get(),
            "timeoutNotifications", timeoutNotificationCount.get(),
            "totalMessages", admissionNotificationCount.get() + rankUpdateCount.get() + 
                           statsUpdateCount.get() + timeoutNotificationCount.get(),
            "lastUpdated", System.currentTimeMillis()
        );
    }

    /**
     * ✅ 새로 추가: 강제 새로고침 알림 (디버깅용)
     */
    public void notifyForceRefresh(String requestId, String reason) {
        try {
            String destination = "/topic/refresh/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "FORCE_REFRESH",
                "action", "RELOAD_PAGE",
                "reason", reason,
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            logger.info("🔄 WEBSOCKET [강제 새로고침]: requestId: {}... | reason: {}", 
                       requestId.substring(0, 8), reason);
                       
        } catch (Exception e) {
            logger.error("❌ 강제 새로고침 알림 실패", e);
        }
    }
}