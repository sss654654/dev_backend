// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    private final DynamicSessionCalculator sessionCalculator;

    public QueueProcessor(AdmissionService admissionService, WebSocketUpdateService webSocketUpdateService, DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
    }

    @Scheduled(fixedRateString = "${queueProcessorInterval:2000}")
    public void processAdmissionQueues() {
        Set<String> movieIds = admissionService.getActiveQueueMovieIds();
        if (movieIds.isEmpty()) return;
        
        for (String movieId : movieIds) {
            try {
                processMovieQueue("movie", movieId);
            } catch (Exception e) {
                logger.error("[{}] 대기열 처리 실패", movieId, e);
            }
        }
    }

    private void processMovieQueue(String type, String movieId) {
        long vacantSlots = admissionService.getVacantSlots(type, movieId);
        if (vacantSlots > 0) {
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            if (waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                if (!admittedUsers.isEmpty()) {
                    logger.info("-> [{}] {}명 입장 처리", movieId, admittedUsers.size());
                    admittedUsers.forEach(member -> {
                        String requestId = member.split(":")[0];
                        webSocketUpdateService.notifyAdmission(requestId, movieId);
                    });
                }
            }
        }

        // 남은 대기자들에게 순위 업데이트
        updateWaitingRanks(type, movieId);
    }

    private void updateWaitingRanks(String type, String movieId) {
        Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
        long totalWaiting = userRanks.size();
        
        if (totalWaiting > 0) {
            logger.debug("[{}] 남은 대기자 {}명에게 순위 업데이트 전송", movieId, totalWaiting);
        }
        
        userRanks.forEach((requestId, rank) -> {
            webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
        });

        // 전체 통계 브로드캐스트
        webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
    }
}