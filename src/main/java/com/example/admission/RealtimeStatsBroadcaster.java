// src/main/java/com/example/admission/RealtimeStatsBroadcaster.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class RealtimeStatsBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(RealtimeStatsBroadcaster.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    
    public RealtimeStatsBroadcaster(AdmissionService admissionService, 
                                  WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
    }
    
    @Scheduled(fixedRate = 2000) // 2초마다 통계 브로드캐스트
    public void broadcastRealtimeStats() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            
            if (movieIds.isEmpty()) {
                return;
            }
            
            int broadcastCount = 0;
            for (String movieId : movieIds) {
                try {
                    long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                    long activeCount = admissionService.getTotalActiveCount("movie", movieId);
                    
                    if (totalWaiting > 0 || activeCount > 0) {
                        webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
                        broadcastCount++;
                        
                        logger.debug("📈 [실시간 통계] movieId={}, 대기={}명, 활성={}명", 
                                   movieId, totalWaiting, activeCount);
                    }
                } catch (Exception e) {
                    logger.error("❌ 영화 {} 통계 브로드캐스트 실패", movieId, e);
                }
            }
            
            if (broadcastCount > 0) {
                logger.debug("📊 [실시간 통계] {}개 영화 통계 브로드캐스트 완료", broadcastCount);
            }
            
        } catch (Exception e) {
            logger.error("❌ 실시간 통계 브로드캐스트 전체 실패", e);
        }
    }
    
    @Scheduled(fixedRate = 5000) // 5초마다 개별 순위 업데이트
    public void updateIndividualRanks() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            
            for (String movieId : movieIds) {
                try {
                    long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                    if (totalWaiting == 0) continue;
                    
                    Map<String, Long> allRanks = admissionService.getAllUserRanks("movie", movieId);
                    
                    for (Map.Entry<String, Long> entry : allRanks.entrySet()) {
                        try {
                            String requestId = entry.getKey();
                            Long rank = entry.getValue();
                            webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
                        } catch (Exception e) {
                            logger.error("❌ 개별 순위 업데이트 실패: requestId={}", entry.getKey(), e);
                        }
                    }
                    
                    logger.debug("🔄 [개별 순위] movieId={}, {}명 순위 업데이트 완료", movieId, allRanks.size());
                    
                } catch (Exception e) {
                    logger.error("❌ 영화 {} 개별 순위 업데이트 실패", movieId, e);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 개별 순위 업데이트 전체 실패", e);
        }
    }
}