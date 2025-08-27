// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService; // WebSocketUpdateService 다시 임포트
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
    private final LoadBalancingOptimizer loadBalancingOptimizer;
    private final KinesisAdmissionProducer kinesisProducer;
    private final WebSocketUpdateService webSocketUpdateService; // WebSocket 서비스 다시 주입

    public QueueProcessor(AdmissionService admissionService, 
                          LoadBalancingOptimizer loadBalancingOptimizer,
                          KinesisAdmissionProducer kinesisProducer,
                          WebSocketUpdateService webSocketUpdateService) { // 생성자 수정
        this.admissionService = admissionService;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
        this.kinesisProducer = kinesisProducer;
        this.webSocketUpdateService = webSocketUpdateService; // 주입
    }

    @Scheduled(fixedRateString = "${queueProcessorInterval:1000}")
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) return;
            
            for (String movieId : movieIds) {
                if (loadBalancingOptimizer.shouldProcessMovie(movieId)) {
                    processMovieQueue("movie", movieId);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 대기열 처리 중 전체 오류 발생", e);
        }
    }

    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    logger.info("🚀 [{}] {}명을 Kinesis로 입장 이벤트 전송", movieId, admittedUsers.size());
                    kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                }
            }
            
            // ⭐ [핵심 기능 추가] 남은 대기자들에게 순위 업데이트 방송
            updateAndBroadcastRank(type, movieId);

        } catch (Exception e) {
            logger.error("❌ [{}] 영화 대기열 처리 중 오류", movieId, e);
        }
    }

    /**
     * 남은 대기자들의 순위를 조회하고 WebSocket으로 업데이트 알림을 보냅니다.
     */
    private void updateAndBroadcastRank(String type, String movieId) {
        long currentTotalWaiting = admissionService.getTotalWaitingCount(type, movieId);
        if (currentTotalWaiting == 0) {
            return; // 대기자가 없으면 알림 불필요
        }

        // 1. 해당 영화 대기열의 모든 사용자에게 현재 총 대기자 수를 브로드캐스트
        webSocketUpdateService.broadcastQueueStats(movieId, currentTotalWaiting);

        // 2. 부하를 고려하여 대기열의 상위 100명에게만 개별 순위 알림
        Map<String, Long> topRanks = admissionService.getAllUserRanks(type, movieId); // 전체 순위 가져오기
        
        logger.debug("[{}] 총 {}명에게 순위 업데이트 알림 전송", movieId, topRanks.size());
        
        for (Map.Entry<String, Long> entry : topRanks.entrySet()) {
            String requestId = entry.getKey();
            Long rank = entry.getValue();
            webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, currentTotalWaiting);
        }
    }
}