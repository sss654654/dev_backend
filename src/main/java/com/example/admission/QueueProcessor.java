// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final KinesisAdmissionProducer kinesisProducer;
    private final WebSocketUpdateService webSocketUpdateService;
    private final DynamicSessionCalculator sessionCalculator;
    
    @Value("${admission.use-kinesis:true}")
    private boolean useKinesis;

    public QueueProcessor(AdmissionService admissionService,
                         KinesisAdmissionProducer kinesisProducer,
                         WebSocketUpdateService webSocketUpdateService,
                         DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.kinesisProducer = kinesisProducer;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
    }

    @Scheduled(fixedRateString = "${queueProcessorInterval:2000}")
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) return;
            
            for (String movieId : movieIds) {
                processMovieQueue("movie", movieId);
            }
        } catch (Exception e) {
            logger.error("대기열 처리 중 오류", e);
        }
    }

    private void processMovieQueue(String type, String movieId) {
        long vacantSlots = admissionService.getVacantSlots(type, movieId);
        if (vacantSlots <= 0) {
            updateWaitingRanks(type, movieId);
            return;
        }
        
        long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
        if (waitingCount == 0) return;
            
        long admitCount = Math.min(vacantSlots, waitingCount);
        List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);

        if (!admittedUsers.isEmpty()) {
            admittedUsers.forEach(member -> {
                String requestId = member.split(":")[0];
                webSocketUpdateService.notifyAdmission(requestId, movieId);
            });
            
            if (useKinesis) {
                try {
                    kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                } catch (Exception e) {
                    logger.error("Kinesis 전송 실패", e);
                }
            }
        }
            
        updateWaitingRanks(type, movieId);
        
        long remainingWaiting = admissionService.getTotalWaitingCount(type, movieId);
        webSocketUpdateService.broadcastQueueStats(movieId, remainingWaiting);
    }

    private void updateWaitingRanks(String type, String movieId) {
        Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
        long totalWaiting = userRanks.size();
        
        userRanks.forEach((requestId, rank) -> {
            webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
        });
    }
}