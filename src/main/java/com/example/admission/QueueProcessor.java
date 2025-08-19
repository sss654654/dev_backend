package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
    }

    @Scheduled(fixedRate = 2000)
    public void processQueues() {
        Set<String> activeMovieQueues = admissionService.getActiveMovieQueues();
        if (activeMovieQueues == null || activeMovieQueues.isEmpty()) {
            return;
        }

        for (String movieId : activeMovieQueues) {
            // 변경점: Service에 위임하여 빈자리 수를 계산
            long vacantSlots = admissionService.getVacantSlots(movieId);

            if (vacantSlots > 0) {
                Set<String> admittedUsers = admissionService.popNextUsersFromQueue(movieId, vacantSlots);

                if (!admittedUsers.isEmpty()) {
                    logger.info("[MovieID: {}] 대기열 처리: {}명 입장", movieId, admittedUsers.size());
                    
                    for (String sessionId : admittedUsers) {
                        admissionService.addToActiveSessions(movieId, sessionId);

                        String eventData = String.format(
                            "{\"action\":\"ADMIT\", \"movieId\":\"%s\", \"sessionId\":\"%s\"}",
                            movieId, sessionId
                        );

                        PutRecordRequest request = PutRecordRequest.builder()
                                .streamName("cgv-admissions-stream")
                                .partitionKey(sessionId)
                                .data(SdkBytes.fromString(eventData, StandardCharsets.UTF_8))
                                .build();
                        
                        kinesisClient.putRecord(request);
                        logger.info("PRODUCER [MovieID: {}]: {}님의 입장 이벤트를 Kinesis로 발행했습니다.", movieId, sessionId);
                    }
                }
            }
            
            admissionService.removeMovieQueueIfEmpty(movieId);
        }
    }
}