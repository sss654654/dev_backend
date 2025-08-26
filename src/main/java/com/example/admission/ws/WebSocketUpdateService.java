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
    
    private final AtomicLong rankUpdateCount = new AtomicLong(0);
    private final AtomicLong statsUpdateCount = new AtomicLong(0);
    private final AtomicLong admissionNotificationCount = new AtomicLong(0);
    private final AtomicLong timeoutNotificationCount = new AtomicLong(0);

    public WebSocketUpdateService(SimpMessagingTemplate template) {
        this.template = template;
    }

    /**
     * 대기열 순위 업데이트를 특정 사용자에게 전송
     */
    public void notifyRankUpdate(String requestId, String status, long rank, long totalWaiting) {
        try {
            String destination = "/topic/rank/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", status,
                "requestId", requestId,
                "rank", rank,
                "totalWaiting", totalWaiting,
                "estimatedWaitTime", calculateEstimatedWaitTime(rank),
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            rankUpdateCount.incrementAndGet();
            
            logger.debug("순위 업데이트 알림 전송 | destination: {} | rank: {}/{} | requestId: {}", 
                        destination, rank, totalWaiting, requestId);
                        
        } catch (Exception e) {
            logger.error("순위 업데이트 알림 전송 실패 - requestId: {}, rank: {}", requestId, rank, e);
        }
    }

    /**
     * 전체 대기열 통계를 영화별로 브로드캐스트
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
            
            logger.debug("대기열 통계 브로드캐스트 | destination: {} | totalWaiting: {} | movieId: {}", 
                        destination, totalWaiting, movieId);
                        
        } catch (Exception e) {
            logger.error("대기열 통계 브로드캐스트 실패 - movieId: {}, totalWaiting: {}", 
                        movieId, totalWaiting, e);
        }
    }

    /**
     * 입장 허가 알림을 특정 사용자에게 전송 (Kinesis Consumer에서 호출)
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
            
            template.convertAndSend(destination, payload);
            admissionNotificationCount.incrementAndGet();
            
            logger.info("입장 허가 알림 전송 | destination: {} | requestId: {} | movieId: {}", 
                       destination, requestId, movieId);
                       
        } catch (Exception e) {
            logger.error("입장 허가 알림 전송 실패 - requestId: {}, movieId: {}", requestId, movieId, e);
        }
    }

    /**
     * 세션 타임아웃으로 퇴장된 사용자에게 알림을 보냅니다.
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
            
            logger.warn("타임아웃 알림 전송 | destination: {} | requestId: {}", 
                       destination, requestId);
                       
        } catch (Exception e) {
            logger.error("타임아웃 알림 전송 실패 - requestId: {}", requestId, e);
        }
    }

    /**
     * 대기열 진입 확인 메시지 (사용자가 대기열에 등록되었을 때)
     */
    public void notifyQueueJoined(String requestId, long position, long totalWaiting) {
        try {
            String destination = "/topic/queue/joined/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "QUEUED",
                "message", "대기열에 등록되었습니다.",
                "requestId", requestId,
                "position", position,
                "totalWaiting", totalWaiting,
                "estimatedWaitTime", calculateEstimatedWaitTime(position),
                "timestamp", System.currentTimeMillis()
            );
            
            template.convertAndSend(destination, payload);
            
            logger.info("대기열 진입 알림 전송 | destination: {} | position: {}/{} | requestId: {}", 
                       destination, position, totalWaiting, requestId);
                       
        } catch (Exception e) {
            logger.error("대기열 진입 알림 전송 실패 - requestId: {}, position: {}", requestId, position, e);
        }
    }

    /**
     * 예상 대기 시간 계산 (분 단위)
     */
    private long calculateEstimatedWaitTime(long position) {
        // 1명당 평균 30초 처리 가정
        return Math.max(1, (position * 30) / 60);
    }

    /**
     * WebSocket 통계 조회
     */
    public Map<String, Long> getStatistics() {
        return Map.of(
            "rankUpdates", rankUpdateCount.get(),
            "statsUpdates", statsUpdateCount.get(),
            "admissionNotifications", admissionNotificationCount.get(),
            "timeoutNotifications", timeoutNotificationCount.get()
        );
    }
}