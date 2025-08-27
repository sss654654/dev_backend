// src/main/java/com/example/admission/KinesisAdmissionProducer.java
package com.example.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class KinesisAdmissionProducer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionProducer.class);
    
    private final KinesisClient kinesisClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${admission.kinesis.stream-name:prod-cgv-admissions-stream}")
    private String streamName;

    public KinesisAdmissionProducer(KinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
        logger.info("🚀 KinesisAdmissionProducer 초기화 완료 - 스트림: {}", streamName);
    }

    /**
     * ✅ 핵심 수정: 여러 사용자 입장 허가 이벤트를 Kinesis로 일괄 전송
     * QueueProcessor에서 호출되어 WebSocket 알림 트리거
     */
    public void publishAdmitEvents(List<String> admittedUsers, String movieId) {
        if (admittedUsers == null || admittedUsers.isEmpty()) {
            logger.debug("🔄 전송할 입장 이벤트가 없음");
            return;
        }

        long startTime = System.currentTimeMillis();
        List<PutRecordsRequestEntry> records = new ArrayList<>();

        logger.info("🎬 [{}] {}명의 입장 허가 이벤트를 Kinesis로 전송 시작", movieId, admittedUsers.size());

        for (String member : admittedUsers) {
            try {
                String[] parts = member.split(":", 2);
                if (parts.length < 2) {
                    logger.warn("⚠️ 잘못된 멤버 형식 무시: {}", member);
                    continue;
                }
                
                String requestId = parts[0];
                String sessionId = parts[1];

                // ✅ 입장 허가 이벤트 페이로드 생성
                Map<String, Object> payload = new HashMap<>();
                payload.put("action", "ADMIT");
                payload.put("requestId", requestId);
                payload.put("sessionId", sessionId);
                payload.put("movieId", movieId);
                payload.put("timestamp", System.currentTimeMillis());
                payload.put("status", "ACTIVE");  // 활성 세션 상태

                String jsonPayload = objectMapper.writeValueAsString(payload);

                records.add(PutRecordsRequestEntry.builder()
                        .partitionKey(requestId)  // requestId 기준으로 분산
                        .data(SdkBytes.fromUtf8String(jsonPayload))
                        .build());
                        
                logger.debug("📝 입장 이벤트 생성: requestId={}..., movieId={}", 
                           requestId.substring(0, 8), movieId);

            } catch (Exception e) {
                logger.error("❌ Kinesis 레코드 생성 실패 - member: {}", member, e);
            }
        }

        if (records.isEmpty()) {
            logger.warn("⚠️ 전송할 유효한 레코드가 없습니다.");
            return;
        }

        // Kinesis에 배치 전송
        try {
            PutRecordsRequest putRecordsRequest = PutRecordsRequest.builder()
                    .streamName(streamName)
                    .records(records)
                    .build();
            
            PutRecordsResponse response = kinesisClient.putRecords(putRecordsRequest);
            long duration = System.currentTimeMillis() - startTime;

            if (response.failedRecordCount() > 0) {
                logger.warn("⚠️ Kinesis 배치 전송 일부 실패. 총 {}건 중 {}건 실패", 
                           records.size(), response.failedRecordCount());
            } else {
                logger.info("✅ [{}] Kinesis 배치 전송 완료: {}건 / {}ms", 
                           movieId, records.size(), duration);
            }

        } catch (Exception e) {
            logger.error("❌ Kinesis 배치 전송 중 심각한 오류 발생", e);
        }
    }

    /**
     * ✅ 새로 추가: 단일 입장 이벤트 전송 (필요시)
     */
    public void publishSingleAdmitEvent(String requestId, String sessionId, String movieId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("action", "ADMIT");
            payload.put("requestId", requestId);
            payload.put("sessionId", sessionId);
            payload.put("movieId", movieId);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("status", "ACTIVE");

            String jsonPayload = objectMapper.writeValueAsString(payload);

            PutRecordRequest request = PutRecordRequest.builder()
                    .streamName(streamName)
                    .partitionKey(requestId)
                    .data(SdkBytes.fromUtf8String(jsonPayload))
                    .build();

            kinesisClient.putRecord(request);
            
            logger.info("✅ [{}] 단일 입장 이벤트 전송 완료: requestId={}...", 
                       movieId, requestId.substring(0, 8));

        } catch (Exception e) {
            logger.error("❌ 단일 입장 이벤트 전송 실패: requestId={}..., movieId={}", 
                        requestId.substring(0, 8), movieId, e);
        }
    }

    /**
     * ✅ 새로 추가: 헬스 체크
     */
    public boolean isKinesisHealthy() {
        try {
            // 간단한 헬스 체크: 스트림 정보 조회
            kinesisClient.describeStream(builder -> builder.streamName(streamName));
            return true;
        } catch (Exception e) {
            logger.error("❌ Kinesis 헬스 체크 실패: {}", e.getMessage());
            return false;
        }
    }
}