package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    private final AdmissionService admissionService;
    private final KinesisClient kinesisClient;

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    // application.yml에서 max-active-sessions 값을 직접 주입받음
    @Value("${admission.max-active-sessions}")
    private long maxActiveSessions;

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
    }

    @Scheduled(fixedRate = 2000)
    public void processQueue() {
        admissionService.cleanupExpiredSessions();

        long activeCount = admissionService.getActiveSessionCount();
        long vacantSlots = maxActiveSessions - activeCount;

        if (vacantSlots > 0) {
            // 1. 대기열에서 다음 사용자를 '꺼내오기만' 합니다. (pop)
            Set<String> candidateUsers = admissionService.popNextUsersFromQueue(vacantSlots);
            
            if (!candidateUsers.isEmpty()) {
                logger.info("{} 명의 입장 후보자를 대기열에서 선정했습니다: {}", candidateUsers.size(), candidateUsers);
                
                for (String sessionId : candidateUsers) {
                    // 2. 🔥 중요: 활성 세션에 추가하는 로직을 제거했습니다!
                    // admissionService.addToActiveSessions(sessionId); // <--- 이 줄이 삭제됨

                    // 3. Kinesis로 "이 사용자를 입장시키세요" 라는 '명령'을 보냅니다.
                    PutRecordRequest request = PutRecordRequest.builder()
                            .streamName(streamName)
                            .partitionKey(sessionId)
                            .data(SdkBytes.fromString("{\"action\":\"ADMIT\", \"sessionId\":\"" + sessionId + "\"}", StandardCharsets.UTF_8))
                            .build();
                    kinesisClient.putRecord(request);
                    logger.info("PRODUCER: {}님의 입장 처리 요청을 Kinesis로 발행했습니다.", sessionId);
                }
            }
        }
    }
}