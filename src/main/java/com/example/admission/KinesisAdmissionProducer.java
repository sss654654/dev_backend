package com.example.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 🔹 개선된 Kinesis Producer - 상세한 로깅과 에러 처리 강화
 */
@Component
public class KinesisAdmissionProducer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionProducer.class);
    
    private final KinesisClient kinesisClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public KinesisAdmissionProducer(KinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
        logger.info("🚀 KinesisAdmissionProducer 초기화 완료 - 스트림: {}", streamName);
    }

    /**
     * 🔹 단일 사용자 입장 허가 이벤트를 Kinesis로 전송 (상세 로깅 추가)
     */
    public void publishAdmitEvent(String requestId, String movieId, String sessionId) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 메시지 구성
            Map<String, Object> eventData = new HashMap<>();
            eventData.put("action", "ADMIT");
            eventData.put("requestId", requestId);
            eventData.put("movieId", movieId);
            eventData.put("sessionId", sessionId);
            eventData.put("timestamp", System.currentTimeMillis());
            eventData.put("source", "queue-processor");
            
            String jsonMessage = objectMapper.writeValueAsString(eventData);
            
            logger.debug("PRODUCER: 전송할 메시지 생성 - requestId: {}, 크기: {} bytes", 
                        requestId, jsonMessage.getBytes().length);
            
            // 2. Kinesis로 전송
            PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                .streamName(streamName)
                .data(SdkBytes.fromUtf8String(jsonMessage))
                .partitionKey(requestId) // requestId를 파티션 키로 사용
                .build();
            
            PutRecordResponse response = kinesisClient.putRecord(putRecordRequest);
            
            long duration = System.currentTimeMillis() - startTime;
            
            // 3. 🚨 성공 로그 강화
            logger.info("✅ PRODUCER: Kinesis 이벤트 전송 성공 | requestId: {} | movieId: {} | " +
                       "shardId: {} | sequenceNumber: {} | 소요시간: {}ms", 
                       requestId, movieId, response.shardId(), response.sequenceNumber(), duration);
                       
            // 4. 메시지 내용도 debug 레벨로 로깅
            logger.debug("PRODUCER: 전송된 메시지 내용 - {}", jsonMessage);
                
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ PRODUCER: Kinesis 이벤트 전송 실패 | requestId: {} | movieId: {} | " +
                        "소요시간: {}ms | 오류: {}", requestId, movieId, duration, e.getMessage(), e);
            
            // 🔹 실패 시 fallback 처리 가능 (예: 재시도 큐에 추가, 직접 WebSocket 전송 등)
            // 여기서는 로깅만 하고 상위에서 처리하도록 예외를 다시 던질 수도 있음
        }
    }

    /**
     * 🔹 배치로 여러 사용자 입장 허가 이벤트 전송 (개선된 로깅)
     */
    public void publishBatchAdmitEvents(Map<String, String> admittedUsers, String movieId) {
        long batchStartTime = System.currentTimeMillis();
        int successCount = 0;
        int failCount = 0;
        
        logger.info("🎯 PRODUCER: 배치 이벤트 전송 시작 - movieId: {}, 대상: {} 명", 
                   movieId, admittedUsers.size());
        
        // 각 사용자마다 개별 이벤트 전송
        for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
            String requestId = entry.getKey();
            String sessionId = entry.getValue();
            
            try {
                publishAdmitEvent(requestId, movieId, sessionId);
                successCount++;
            } catch (Exception e) {
                failCount++;
                logger.warn("PRODUCER: 개별 이벤트 전송 실패 - requestId: {}", requestId);
            }
        }
        
        long batchDuration = System.currentTimeMillis() - batchStartTime;
        
        // 🚨 배치 전송 결과 상세 로깅
        logger.info("📊 PRODUCER: 배치 이벤트 전송 완료 | movieId: {} | 성공: {}/{} | " +
                   "실패: {} | 총 소요시간: {}ms | 평균: {}ms/건", 
                   movieId, successCount, admittedUsers.size(), failCount, batchDuration,
                   admittedUsers.size() > 0 ? batchDuration / admittedUsers.size() : 0);
    }

    /**
     * 🔹 비동기 전송 (필요 시 사용)
     */
    public CompletableFuture<Void> publishAdmitEventAsync(String requestId, String movieId, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            logger.debug("PRODUCER: 비동기 이벤트 전송 시작 - requestId: {}", requestId);
            publishAdmitEvent(requestId, movieId, sessionId);
        });
    }

    /**
     * 🔹 Kinesis 스트림 상태 확인 (헬스체크용)
     */
    public boolean isKinesisHealthy() {
        try {
            // 간단한 더미 메시지로 연결 테스트
            Map<String, Object> healthCheck = Map.of(
                "action", "HEALTH_CHECK",
                "timestamp", System.currentTimeMillis()
            );
            
            String testMessage = objectMapper.writeValueAsString(healthCheck);
            
            PutRecordRequest testRequest = PutRecordRequest.builder()
                .streamName(streamName)
                .data(SdkBytes.fromUtf8String(testMessage))
                .partitionKey("health-check")
                .build();
            
            kinesisClient.putRecord(testRequest);
            logger.debug("PRODUCER: Kinesis 연결 상태 정상");
            return true;
            
        } catch (Exception e) {
            logger.error("PRODUCER: Kinesis 연결 상태 불량 - {}", e.getMessage());
            return false;
        }
    }
}