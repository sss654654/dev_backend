// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final LoadBalancingOptimizer loadBalancingOptimizer;
    private final KinesisAdmissionProducer kinesisProducer;
    private final WebSocketUpdateService webSocketUpdateService;

    public QueueProcessor(AdmissionService admissionService, 
                          LoadBalancingOptimizer loadBalancingOptimizer,
                          KinesisAdmissionProducer kinesisProducer,
                          WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
        this.kinesisProducer = kinesisProducer;
        this.webSocketUpdateService = webSocketUpdateService;
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

    // ===============================================
    // 🔥 3. QueueProcessor 연동 개선
    // ===============================================
    // QueueProcessor.java의 processMovieQueue 메서드를 다음과 같이 수정:

    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            boolean admitted = false;
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    admitted = true;
                    
                    // ⭐ 개선: Kinesis 전송을 비동기로 처리 (블로킹 방지)
                    CompletableFuture.runAsync(() -> {
                        try {
                            kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                            logger.debug("📤 [Kinesis] 입장 허가 이벤트 전송: {}명", admittedUsers.size());
                        } catch (Exception e) {
                            logger.error("❌ Kinesis 입장 허가 전송 실패", e);
                        }
                    });
                    
                    // 즉시 WebSocket 전송 (동기식 - 빠른 응답)
                    for (String member : admittedUsers) {
                        try {
                            String requestId = member.split(":")[0];
                            webSocketUpdateService.notifyAdmission(requestId, movieId);
                            logger.info("🎬 [직접 전송] 입장 허가: requestId={}...", 
                                    requestId.substring(0, 8));
                        } catch (Exception e) {
                            logger.error("❌ WebSocket 전송 실패: member={}", member, e);
                        }
                    }
                }
            }
            
            // 순위 및 통계 업데이트
            if (admitted || waitingCount > 0) {
                long currentTotalWaiting = admissionService.getTotalWaitingCount(type, movieId);
                Map<String, Long> allRanks = admissionService.getAllUserRanks(type, movieId);
                
                // ⭐ 개선: 순위 업데이트도 비동기로 처리
                CompletableFuture.runAsync(() -> {
                    try {
                        kinesisProducer.publishRankUpdateEvents(movieId, currentTotalWaiting, allRanks);
                        logger.debug("📤 [Kinesis] 순위 업데이트: {}명", allRanks.size());
                    } catch (Exception e) {
                        logger.error("❌ Kinesis 순위 업데이트 실패", e);
                    }
                });
                
                // 즉시 WebSocket 전송
                broadcastRankUpdatesDirectly(movieId, allRanks, currentTotalWaiting);
            }

        } catch (Exception e) {
            logger.error("❌ [{}] 영화 대기열 처리 중 오류", movieId, e);
        }
    }
    
    private void broadcastRankUpdatesDirectly(String movieId, Map<String, Long> allRanks, long totalWaiting) {
        try {
            // 전체 통계 브로드캐스트
            webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
            logger.debug("📈 [직접 전송] 통계 브로드캐스트: movieId={}, 총 대기자={}명", movieId, totalWaiting);
            
            // 개별 사용자 순위 업데이트
            for (Map.Entry<String, Long> entry : allRanks.entrySet()) {
                try {
                    String requestId = entry.getKey();
                    Long rank = entry.getValue();
                    webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
                } catch (Exception e) {
                    logger.error("❌ 개별 순위 업데이트 실패: requestId={}", entry.getKey(), e);
                }
            }
            
            logger.debug("📊 [직접 전송] 개별 순위 업데이트 완료: {}명", allRanks.size());
            
        } catch (Exception e) {
            logger.error("❌ 직접 순위 업데이트 전송 실패: movieId={}", movieId, e);
        }
    }
}