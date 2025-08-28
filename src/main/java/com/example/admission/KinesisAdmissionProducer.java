
// ===============================================
// 🔥 2. KinesisAdmissionProducer 완전 리팩토링  
// ===============================================
package com.example.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class KinesisAdmissionProducer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionProducer.class);
    
    private final KinesisClient kinesisClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${KINESIS_STREAM_NAME:cgv-admissions-stream}")
    private String streamName;

    public KinesisAdmissionProducer(KinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
    }

    public void publishAdmitEvents(List<String> admittedUsers, String movieId) {
        if (admittedUsers == null || admittedUsers.isEmpty()) return;

        List<PutRecordsRequestEntry> records = new ArrayList<>();
        for (String member : admittedUsers) {
            try {
                String requestId = member.split(":")[0];
                Map<String, Object> payload = Map.of(
                    "action", "ADMIT",
                    "requestId", requestId,
                    "movieId", movieId,
                    "timestamp", System.currentTimeMillis()
                );
                records.add(createRecordEntry(requestId, objectMapper.writeValueAsString(payload)));
            } catch (Exception e) {
                logger.error("❌ Kinesis 입장 레코드 생성 실패 - member: {}", member, e);
            }
        }
        sendToKinesis(records, "입장 허가");
    }

    public void publishRankUpdateEvents(String movieId, long totalWaiting, Map<String, Long> ranks) {
        List<PutRecordsRequestEntry> records = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // 1. 전체 통계 브로드캐스트 이벤트
        try {
            Map<String, Object> statsPayload = Map.of(
                "action", "STATS_UPDATE", 
                "movieId", movieId, 
                "totalWaiting", totalWaiting, 
                "timestamp", timestamp);
            records.add(createRecordEntry(movieId, objectMapper.writeValueAsString(statsPayload)));
        } catch (Exception e) {
            logger.error("❌ Kinesis 통계 레코드 생성 실패", e);
        }

        // 2. 개인별 순위 업데이트 이벤트
        if (ranks != null) {
            for (Map.Entry<String, Long> entry : ranks.entrySet()) {
                try {
                    String requestId = entry.getKey();
                    Map<String, Object> rankPayload = Map.of(
                        "action", "RANK_UPDATE", 
                        "requestId", requestId, 
                        "rank", entry.getValue(), 
                        "totalWaiting", totalWaiting, 
                        "timestamp", timestamp);
                    records.add(createRecordEntry(requestId, objectMapper.writeValueAsString(rankPayload)));
                } catch (Exception e) {
                    logger.error("❌ Kinesis 순위 레코드 생성 실패 - requestId: {}", entry.getKey(), e);
                }
            }
        }
        sendToKinesis(records, "순위/통계 업데이트");
    }

    // ⭐ 핵심 개선 1: 균등 분산을 위한 파티션 키 생성
    private PutRecordsRequestEntry createRecordEntry(String originalKey, String data) {
        String partitionKey = generateBalancedPartitionKey(originalKey);
        
        return PutRecordsRequestEntry.builder()
                .partitionKey(partitionKey)
                .data(SdkBytes.fromUtf8String(data))
                .build();
    }
    
    /**
     * 균등 분산을 위한 파티션 키 생성
     * requestId의 해시를 이용해 샤드 전체에 고르게 분산
     */
    private String generateBalancedPartitionKey(String originalKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(originalKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 8);
        } catch (Exception e) {
            // 실패 시 UUID 사용 (완전 랜덤 분산)
            return UUID.randomUUID().toString().substring(0, 8);
        }
    }

    // ⭐ 핵심 개선 2: 배치 전송 시 재시도 로직 추가
    private void sendToKinesis(List<PutRecordsRequestEntry> records, String eventType) {
        if (records.isEmpty()) return;
        
        int maxRetries = 3;
        int retryCount = 0;
        List<PutRecordsRequestEntry> recordsToSend = new ArrayList<>(records);
        
        while (retryCount < maxRetries && !recordsToSend.isEmpty()) {
            try {
                PutRecordsRequest request = PutRecordsRequest.builder()
                        .streamName(streamName)
                        .records(recordsToSend)
                        .build();
                        
                PutRecordsResponse response = kinesisClient.putRecords(request);
                
                // 실패한 레코드가 있으면 재시도 준비
                if (response.failedRecordCount() > 0) {
                    recordsToSend = getFailedRecords(recordsToSend, response);
                    retryCount++;
                    
                    logger.warn("⚠️ Kinesis 부분 실패 ({}건), 재시도 {}/{}", 
                               response.failedRecordCount(), retryCount, maxRetries);
                    
                    // 지수 백오프 대기
                    long waitTime = Math.min(100L * (1L << retryCount), 2000L);
                    Thread.sleep(waitTime);
                    continue;
                }
                
                logger.info("✅ Kinesis {} 전송 성공: {}건", eventType, records.size());
                return;
                
            } catch (ProvisionedThroughputExceededException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    logger.error("❌ Kinesis {} 전송 처리량 초과로 최종 실패", eventType);
                    return;
                }
                
                long waitTime = Math.min(500L * retryCount, 3000L);
                logger.warn("⚠️ Kinesis 처리량 초과, {}ms 대기 후 재시도 {}/{}", 
                           waitTime, retryCount, maxRetries);
                try { 
                    Thread.sleep(waitTime); 
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    logger.error("❌ Kinesis {} 전송 최종 실패", eventType, e);
                    return;
                }
                logger.warn("⚠️ Kinesis 전송 실패, 재시도 {}/{}", retryCount, maxRetries, e);
                try { 
                    Thread.sleep(200L * retryCount); 
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        
        if (!recordsToSend.isEmpty()) {
            logger.error("❌ Kinesis {} 일부 레코드 전송 실패: {}건", eventType, recordsToSend.size());
        }
    }
    
    /**
     * 실패한 레코드들만 추출하여 재시도 대상 생성
     */
    private List<PutRecordsRequestEntry> getFailedRecords(List<PutRecordsRequestEntry> originalRecords, 
                                                          PutRecordsResponse response) {
        List<PutRecordsRequestEntry> failedRecords = new ArrayList<>();
        List<PutRecordsResultEntry> results = response.records();
        
        for (int i = 0; i < results.size() && i < originalRecords.size(); i++) {
            PutRecordsResultEntry result = results.get(i);
            if (result.errorCode() != null) {
                failedRecords.add(originalRecords.get(i));
            }
        }
        
        return failedRecords;
    }
    
    // 모니터링을 위한 상태 조회 메서드
    public boolean isKinesisHealthy() {
        try {
            DescribeStreamRequest request = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();
            DescribeStreamResponse response = kinesisClient.describeStream(request);
            return response.streamDescription().streamStatus() == StreamStatus.ACTIVE;
        } catch (Exception e) {
            logger.error("Kinesis 헬스체크 실패", e);
            return false;
        }
    }
}