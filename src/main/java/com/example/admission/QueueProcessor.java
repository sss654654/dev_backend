package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.websockets.LiveUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    private final AdmissionService admissionService;
    private final KinesisClient kinesisClient;
    private final LiveUpdateService liveUpdateService;

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient, LiveUpdateService liveUpdateService) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
        this.liveUpdateService = liveUpdateService;
    }

    @Scheduled(fixedRate = 2000)
    public void processQueues() {
        final String type = "movie"; // 이 프로세서는 영화 대기열만 처리
        Set<String> activeMovieIds = admissionService.getActiveQueues(type);
        if (activeMovieIds == null || activeMovieIds.isEmpty()) {
            return;
        }

        for (String movieId : activeMovieIds) {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);

            if (vacantSlots > 0) {
                Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, vacantSlots);

                if (!admittedUsers.isEmpty()) {
                    logger.info("[{}:{}] 대기열 처리: {}명 입장", type, movieId, admittedUsers.size());
                    for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                        String requestId = entry.getKey();
                        String sessionId = entry.getValue();

                        admissionService.addToActiveSessions(type, movieId, sessionId, requestId);

                        String eventData = String.format(
                            "{\"action\":\"ADMIT\", \"movieId\":\"%s\", \"sessionId\":\"%s\", \"requestId\":\"%s\"}",
                            movieId, sessionId, requestId
                        );

                        PutRecordRequest request = PutRecordRequest.builder()
                                .streamName(streamName)
                                .partitionKey(sessionId)
                                .data(SdkBytes.fromString(eventData, StandardCharsets.UTF_8))
                                .build();
                        
                        kinesisClient.putRecord(request);
                        logger.info("PRODUCER [MovieID: {}]: requestId={} 님의 입장 이벤트를 Kinesis로 발행했습니다.", movieId, requestId);
                    }
                }
            }
            
            Map<String, String> waitingUsers = admissionService.getWaitingUsers(type, movieId);
            if (!waitingUsers.isEmpty()) {
                for (String requestId : waitingUsers.keySet()) {
                    Long rank = admissionService.getUserRank(type, movieId, requestId);
                    if (rank != null) {
                        liveUpdateService.notifyRankUpdate(requestId, rank + 1);
                    }
                }
            }
            
            admissionService.removeQueueIfEmpty(type, movieId);
        }
    }
}