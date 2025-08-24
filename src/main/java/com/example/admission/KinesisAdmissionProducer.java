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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Kinesis Producer - 입장 허가 이벤트를 Kinesis 스트림으로 전송
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
    }

    /**
     * 사용자 입장 허가 이벤트를 Kinesis로 전송
     * @param requestId 사용자 요청 ID
     * @param movieId 영화 ID
     * @param sessionId 세션 ID
     */
    public void publishAdmitEvent(String requestId, String movieId, String sessionId) {
        try {
            // 메시지 구성
            Map<String, Object> eventData = Map.of(
                "action", "ADMIT",
                "requestId", requestId,
                "movieId", movieId,
                "sessionId", sessionId,
                "timestamp", System.currentTimeMillis()
            );
            
            String jsonMessage = objectMapper.writeValueAsString(eventData);
            
            // Kinesis로 전송
            PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                .streamName(streamName)
                .data(SdkBytes.fromUtf8String(jsonMessage))
                .partitionKey(requestId) // requestId를 파티션 키로 사용
                .build();
            
            PutRecordResponse response = kinesisClient.putRecord(putRecordRequest);
            
            logger.info("PRODUCER: Kinesis 이벤트 전송 성공 -> requestId: {}, shardId: {}, sequenceNumber: {}", 
                requestId, response.shardId(), response.sequenceNumber());
                
        } catch (Exception e) {
            logger.error("PRODUCER: Kinesis 이벤트 전송 실패 -> requestId: {}", requestId, e);
            // 실패 시 fallback으로 직접 WebSocket 전송하거나 재시도 로직 추가 가능
        }
    }

    /**
     * 배치로 여러 사용자 입장 허가 이벤트 전송
     * @param admittedUsers requestId -> sessionId 맵
     * @param movieId 영화 ID
     */
    public void publishBatchAdmitEvents(Map<String, String> admittedUsers, String movieId) {
        // 각 사용자마다 개별 이벤트 전송
        admittedUsers.forEach((requestId, sessionId) -> {
            publishAdmitEvent(requestId, movieId, sessionId);
        });
        
        logger.info("PRODUCER: 배치 이벤트 전송 완료 - {}명의 사용자", admittedUsers.size());
    }

    /**
     * 비동기 전송 (옵션)
     */
    public CompletableFuture<Void> publishAdmitEventAsync(String requestId, String movieId, String sessionId) {
        return CompletableFuture.runAsync(() -> publishAdmitEvent(requestId, movieId, sessionId));
    }
}