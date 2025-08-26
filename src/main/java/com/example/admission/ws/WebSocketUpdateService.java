// src/main/java/com/example/admission/ws/WebSocketUpdateService.java - 메시지 전송 개선

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
     * 🎯 [핵심 수정] 입장 허가 알림을 특정 사용자에게 전송 (중복 전송 및 로깅 강화)
     */
    public void notifyAdmission(String requestId, String movieId) {
        try {
            String destination = "/topic/admission/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "ADMITTED",
                "requestId", requestId,
                "movieId", movieId,
                "message", "입장이 허가되었습니다! 좌석 선택 페이지로 이동합니다.",
                "timestamp", System.currentTimeMillis()
            );
            
            // 🔧 메시지 전송 전 상세 로깅
            logger.info("🎬 WEBSOCKET [BEFORE SEND]: 입장 허가 알림 전송 시도 | destination: {} | requestId: {}... | movieId: {}", 
                    destination, requestId.substring(0, 8), movieId);
            logger.debug("🎬 WEBSOCKET [PAYLOAD]: {}", payload);
            
            // 실제 메시지 전송
            template.convertAndSend(destination, payload);
            admissionNotificationCount.incrementAndGet();
            
            // 🔧 메시지 전송 후 상세 로깅
            logger.info("🎬 WEBSOCKET [AFTER SEND]: 입장 허가 알림 전송 완료 | destination: {} | requestId: {}... | movieId: {} | 총 {}번째", 
                    destination, requestId.substring(0, 8), movieId, admissionNotificationCount.get());
                    
            // 🔧 추가: 메시지 전송 성공 확인을 위한 추가 로그
            logger.info("🎬 WEBSOCKET [SUCCESS]: Template.convertAndSend() 성공적으로 호출됨");
                    
        } catch (Exception e) {
            logger.error("❌ WEBSOCKET [ERROR]: 입장 허가 알림 전송 실패 - requestId: {}..., movieId: {}", 
                    requestId.substring(0, 8), movieId, e);
        }
    }

    /**
     * 🔄 순위 업데이트를 특정 사용자에게 전송
     */
    public void notifyRankUpdate(String requestId, String status, long rank, long totalWaiting) {
        try {
            String destination = "/topic/rank/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", status,
                "rank", rank,
                "totalWaiting", totalWaiting,
                "timestamp", System.currentTimeMillis(),
                "requestId", requestId
            );
            
            template.convertAndSend(destination, payload);
            rankUpdateCount.incrementAndGet();
            
            logger.debug("📊 WEBSOCKET: 순위 업데이트 전송 완료 | destination: {} | requestId: {}... | rank: {}/{}", 
                        destination, requestId.substring(0, 8), rank, totalWaiting);
                        
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
            
            logger.debug("📈 WEBSOCKET: 대기열 통계 브로드캐스트 | destination: {} | totalWaiting: {} | movieId: {}", 
                        destination, totalWaiting, movieId);
                        
        } catch (Exception e) {
            logger.error("❌ 대기열 통계 브로드캐스트 실패 - movieId: {}, totalWaiting: {}", 
                        movieId, totalWaiting, e);
        }
    }

    /**
     * ⏰ 세션 타임아웃으로 퇴장된 사용자에게 알림을 보냅니다.
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
            
            logger.warn("⏰ WEBSOCKET: 타임아웃 알림 전송 | destination: {} | requestId: {}...", 
                       destination, requestId.substring(0, 8));
                       
        } catch (Exception e) {
            logger.error("❌ 타임아웃 알림 전송 실패 - requestId: {}...", requestId.substring(0, 8), e);
        }
    }

    /**
     * 📋 대기열 진입 확인 메시지 (사용자가 대기열에 등록되었을 때)
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
            
            logger.info("📋 WEBSOCKET: 대기열 진입 확인 전송 | destination: {} | requestId: {}... | position: {}/{}", 
                       destination, requestId.substring(0, 8), position, totalWaiting);
                       
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
                           statsUpdateCount.get() + timeoutNotificationCount.get()
        );
    }

    /**
     * 🔧 [디버깅용] 테스트 메시지 전송
     */
    public void sendTestMessage(String requestId, String testMessage) {
        try {
            String destination = "/topic/test/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "TEST",
                "message", testMessage,
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            logger.info("🧪 TEST MESSAGE 전송 완료 | destination: {} | message: {}", destination, testMessage);
            
        } catch (Exception e) {
            logger.error("❌ 테스트 메시지 전송 실패", e);
        }
    }
}