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

    public QueueProcessor(AdmissionService admissionService, 
                         WebSocketUpdateService webSocketUpdateService, 
                         DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
    }

    // ✅ 수정: 더 자주 실행하여 빠른 처리 (1초마다)
    @Scheduled(fixedRateString = "${queueProcessorInterval:1000}")
    public void processAdmissionQueues() {
        Set<String> movieIds = admissionService.getActiveQueueMovieIds();
        if (movieIds.isEmpty()) return;
        
        logger.debug("🔄 대기열 처리 시작 - 활성 영화 {}개", movieIds.size());
        
        for (String movieId : movieIds) {
            try {
                processMovieQueue("movie", movieId);
            } catch (Exception e) {
                logger.error("❌ [{}] 대기열 처리 실패", movieId, e);
            }
        }
    }

    private void processMovieQueue(String type, String movieId) {
        // 1. 빈자리 확인
        long vacantSlots = admissionService.getVacantSlots(type, movieId);
        long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
        long activeCount = admissionService.getTotalActiveCount(type, movieId);
        
        logger.debug("📊 [{}] 현황 - 활성: {}, 대기: {}, 빈자리: {}", 
                    movieId, activeCount, waitingCount, vacantSlots);
        
        // 2. 빈자리가 있고 대기자가 있으면 처리
        if (vacantSlots > 0 && waitingCount > 0) {
            long admitCount = Math.min(vacantSlots, waitingCount);
            
            // ✅ 핵심: 대기자들을 활성 세션으로 승격
            List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
            
            if (!admittedUsers.isEmpty()) {
                logger.info("🚀 [{}] {}명을 대기열에서 활성 세션으로 승격", movieId, admittedUsers.size());
                
                // ✅ 중요: 승격된 사용자들에게 입장 허가 WebSocket 알림 전송
                for (String member : admittedUsers) {
                    try {
                        String[] parts = member.split(":", 2);
                        if (parts.length >= 1) {
                            String requestId = parts[0];
                            
                            // 🎯 핵심: WebSocket으로 입장 허가 알림 → WaitPage에서 SeatsPage로 이동
                            webSocketUpdateService.notifyAdmission(requestId, movieId);
                            
                            logger.info("✅ [{}] 입장 허가 알림 전송 완료 - requestId: {}...", 
                                       movieId, requestId.substring(0, 8));
                        }
                    } catch (Exception e) {
                        logger.error("❌ [{}] 입장 알림 전송 실패 - member: {}", movieId, member, e);
                    }
                }
            }
        }

        // 3. 남은 대기자들에게 순위 업데이트
        updateWaitingRanks(type, movieId);
    }

    /**
     * ✅ 수정: 대기자들에게 실시간 순위 업데이트
     */
    private void updateWaitingRanks(String type, String movieId) {
        Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
        long totalWaiting = userRanks.size();
        
        if (totalWaiting > 0) {
            logger.debug("📊 [{}] 대기자 {}명에게 순위 업데이트 전송", movieId, totalWaiting);
            
            // 각 대기자에게 개별 순위 알림
            userRanks.forEach((requestId, rank) -> {
                try {
                    webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
                } catch (Exception e) {
                    logger.error("❌ [{}] 순위 업데이트 실패 - requestId: {}...", 
                               movieId, requestId.substring(0, 8), e);
                }
            });
        }

        // 전체 통계 브로드캐스트
        webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
    }

    /**
     * ✅ 새로 추가: 시스템 상태 체크 (5초마다)
     */
    @Scheduled(fixedRate = 5000)
    public void logSystemStatus() {
        try {
            Set<String> activeMovies = admissionService.getActiveQueueMovieIds();
            long totalActive = 0;
            long totalWaiting = 0;
            
            for (String movieId : activeMovies) {
                long active = admissionService.getTotalActiveCount("movie", movieId);
                long waiting = admissionService.getTotalWaitingCount("movie", movieId);
                totalActive += active;
                totalWaiting += waiting;
            }
            
            long maxSessions = sessionCalculator.calculateMaxActiveSessions();
            
            logger.info("🔍 시스템 현황 - 활성: {}/{}, 대기: {}, 영화: {}개", 
                       totalActive, maxSessions, totalWaiting, activeMovies.size());
                       
        } catch (Exception e) {
            logger.error("❌ 시스템 상태 체크 실패", e);
        }
    }
}