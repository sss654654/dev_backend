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
    
    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public KinesisAdmissionProducer(KinesisClient kinesisClient) {
        this.kinesisClient = kinesisClient;
        logger.info("🚀 KinesisAdmissionProducer 초기화 완료 - 스트림: {}", streamName);
    }

    // ✨✨✨ 핵심 수정: 여러 사용자를 한 번에 Kinesis로 보내는 배치(Batch) 메소드 추가 ✨✨✨
    /**
     * 🔹 여러 사용자 입장 허가 이벤트를 Kinesis로 일괄 전송합니다.
     * @param admittedUsers 입장시킬 사용자 목록 ("requestId:sessionId" 형태)
     * @param movieId 영화 ID
     */
    public void publishAdmitEvents(List<String> admittedUsers, String movieId) {
        if (admittedUsers == null || admittedUsers.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        List<PutRecordsRequestEntry> records = new ArrayList<>();

        for (String member : admittedUsers) {
            try {
                String[] parts = member.split(":", 2);
                if (parts.length < 2) continue;
                String requestId = parts[0];
                String sessionId = parts[1];

                Map<String, Object> payload = new HashMap<>();
                payload.put("action", "ADMIT");
                payload.put("requestId", requestId);
                payload.put("sessionId", sessionId);
                payload.put("movieId", movieId);
                payload.put("timestamp", System.currentTimeMillis());

                String jsonPayload = objectMapper.writeValueAsString(payload);

                records.add(PutRecordsRequestEntry.builder()
                        .partitionKey(requestId) // 각 이벤트를 requestId 기준으로 분산
                        .data(SdkBytes.fromUtf8String(jsonPayload))
                        .build());
            } catch (Exception e) {
                logger.error("PRODUCER: Kinesis 레코드 생성 실패 - member: {}", member, e);
            }
        }

        if (records.isEmpty()) {
            logger.warn("PRODUCER: 전송할 유효한 레코드가 없습니다.");
            return;
        }

        PutRecordsRequest putRecordsRequest = PutRecordsRequest.builder()
                .streamName(streamName)
                .records(records)
                .build();
        
        try {
            PutRecordsResponse response = kinesisClient.putRecords(putRecordsRequest);
            long duration = System.currentTimeMillis() - startTime;

            if (response.failedRecordCount() > 0) {
                logger.warn("PRODUCER: Kinesis 배치 전송 일부 실패. 총 {}건 중 {}건 실패.", 
                           records.size(), response.failedRecordCount());
            }
            logger.info("PRODUCER: Kinesis 배치 전송 완료. {}건 / {}ms", records.size(), duration);
        } catch (Exception e) {
            logger.error("PRODUCER: Kinesis 배치 전송 중 심각한 오류 발생", e);
        }
    }
}