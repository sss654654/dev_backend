package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.websockets.LiveUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final KinesisClient kinesisClient;
    private final LiveUpdateService liveUpdateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 성능 모니터링용 카운터
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient, LiveUpdateService liveUpdateService) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
        this.liveUpdateService = liveUpdateService;
    }

    @Scheduled(fixedRate = 2000)
    public void processQueues() {
        final String type = "movie";
        
        try {
            Set<String> activeMovieIds = admissionService.getActiveQueues(type);
            if (activeMovieIds == null || activeMovieIds.isEmpty()) {
                return;
            }

            for (String movieId : activeMovieIds) {
                processMovieQueue(type, movieId);
            }
            
            // 주기적으로 통계 로그 출력 (5분마다)
            if (processedCount.get() % 150 == 0 && processedCount.get() > 0) {
                logger.info("Queue Processor 통계 - 처리: {}, 에러: {}", 
                    processedCount.get(), errorCount.get());
            }
            
        } catch (Exception e) {
            logger.error("Queue processing 전체 오류", e);
            errorCount.incrementAndGet();
        }
    }
    
    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);

            if (vacantSlots > 0) {
                Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, vacantSlots);

                if (!admittedUsers.isEmpty()) {
                    logger.info("[{}:{}] 대기열 처리: {}명 입장", type, movieId, admittedUsers.size());
                    
                    for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                        String requestId = entry.getKey();
                        String sessionId = entry.getValue();
                        
                        // 활성 세션에 추가
                        admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                        
                        // Kinesis 이벤트 발송
                        sendAdmissionEvent(movieId, sessionId, requestId);
                        
                        processedCount.incrementAndGet();
                    }
                }
            }

            // 대기 중인 사용자들에게 순위 업데이트 알림
            updateWaitingUsersRank(type, movieId);
            
            // 빈 큐 정리
            admissionService.removeQueueIfEmpty(type, movieId);
            
        } catch (Exception e) {
            logger.error("Movie queue processing 오류: movieId={}", movieId, e);
            errorCount.incrementAndGet();
        }
    }
    
    private void sendAdmissionEvent(String movieId, String sessionId, String requestId) {
        try {
            // 구조화된 이벤트 데이터 생성
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("action", "ADMIT");
            eventData.put("movieId", movieId);
            eventData.put("sessionId", sessionId);
            eventData.put("requestId", requestId);
            eventData.put("timestamp", System.currentTimeMillis());
            eventData.put("eventId", java.util.UUID.randomUUID().toString());
            
            // JSON으로 직렬화
            String eventJson = objectMapper.writeValueAsString(eventData);
            
            PutRecordRequest request = PutRecordRequest.builder()
                    .streamName(streamName)
                    .partitionKey(sessionId) // 세션별 순서 보장
                    .data(SdkBytes.fromString(eventJson, StandardCharsets.UTF_8))
                    .build();

            PutRecordResponse response = kinesisClient.putRecord(request);
            
            logger.debug("PRODUCER [MovieID: {}]: requestId={} 님의 입장 이벤트를 Kinesis로 발행했습니다. (Shard: {}, Sequence: {})", 
                movieId, requestId, response.shardId(), response.sequenceNumber());
                
        } catch (Exception e) {
            logger.error("Kinesis 이벤트 발송 실패: movieId={}, requestId={}", movieId, requestId, e);
            errorCount.incrementAndGet();
            
            // Kinesis 실패 시 WebSocket으로 직접 알림 (fallback)
            try {
                liveUpdateService.notifyAdmitted(requestId, movieId, sessionId);
                logger.warn("Kinesis 실패로 WebSocket 직접 알림 처리: requestId={}", requestId);
            } catch (Exception fallbackError) {
                logger.error("WebSocket fallback도 실패: requestId={}", requestId, fallbackError);
            }
        }
    }
    
    private void updateWaitingUsersRank(String type, String movieId) {
        try {
            Map<String, String> waitingUsers = admissionService.getWaitingUsers(type, movieId);
            if (!waitingUsers.isEmpty()) {
                for (String requestId : waitingUsers.keySet()) {
                    Long rank = admissionService.getUserRank(type, movieId, requestId);
                    if (rank != null) {
                        liveUpdateService.notifyRankUpdate(requestId, rank + 1);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
    
    /**
     * 헬스체크용 메서드
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> health = new HashMap<>();
        health.put("processedCount", processedCount.get());
        health.put("errorCount", errorCount.get());
        health.put("errorRate", processedCount.get() > 0 ? 
            (double) errorCount.get() / processedCount.get() : 0.0);
        health.put("status", errorCount.get() < processedCount.get() * 0.1 ? "HEALTHY" : "DEGRADED");
        return health;
    }
}