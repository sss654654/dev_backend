// src/main/java/com/example/admission/KinesisAdmissionProducer.java
package com.example.admission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class KinesisAdmissionProducer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionProducer.class);
    
    private final KinesisClient kinesisClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${KINESIS_STREAM_NAME:prod-cgv-admissions-stream}")
    private String streamName;

    public KinesisAdmissionProducer(KinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
    }

    /**
     * 입장 허가 이벤트를 Kinesis로 발행합니다.
     */
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

    /**
     * ⭐ 순위 및 통계 업데이트 이벤트를 Kinesis로 발행하는 새로운 메서드
     */
    public void publishRankUpdateEvents(String movieId, long totalWaiting, Map<String, Long> ranks) {
        List<PutRecordsRequestEntry> records = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // 1. 전체 통계 브로드캐스트 이벤트
        try {
            Map<String, Object> statsPayload = Map.of("action", "STATS_UPDATE", "movieId", movieId, "totalWaiting", totalWaiting, "timestamp", timestamp);
            records.add(createRecordEntry(movieId, objectMapper.writeValueAsString(statsPayload)));
        } catch (Exception e) {
            logger.error("❌ Kinesis 통계 레코드 생성 실패", e);
        }

        // 2. 개인별 순위 업데이트 이벤트
        if (ranks != null) {
            for (Map.Entry<String, Long> entry : ranks.entrySet()) {
                try {
                    String requestId = entry.getKey();
                    Map<String, Object> rankPayload = Map.of("action", "RANK_UPDATE", "requestId", requestId, "rank", entry.getValue(), "totalWaiting", totalWaiting, "timestamp", timestamp);
                    records.add(createRecordEntry(requestId, objectMapper.writeValueAsString(rankPayload)));
                } catch (Exception e) {
                    logger.error("❌ Kinesis 순위 레코드 생성 실패 - requestId: {}", entry.getKey(), e);
                }
            }
        }
        sendToKinesis(records, "순위/통계 업데이트");
    }

    private PutRecordsRequestEntry createRecordEntry(String partitionKey, String data) {
        return PutRecordsRequestEntry.builder()
                .partitionKey(partitionKey)
                .data(SdkBytes.fromUtf8String(data))
                .build();
    }

    private void sendToKinesis(List<PutRecordsRequestEntry> records, String eventType) {
        if (records.isEmpty()) return;
        try {
            PutRecordsRequest putRecordsRequest = PutRecordsRequest.builder()
                    .streamName(streamName)
                    .records(records)
                    .build();
            kinesisClient.putRecords(putRecordsRequest);
            logger.info("✅ Kinesis {} 이벤트 전송 완료: {}건", eventType, records.size());
        } catch (Exception e) {
            logger.error("❌ Kinesis {} 이벤트 전송 실패", eventType, e);
        }
    }
}