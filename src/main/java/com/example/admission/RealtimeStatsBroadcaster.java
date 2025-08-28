
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeStatsBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(RealtimeStatsBroadcaster.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    
    // ✅ 이전 순위를 캐시하여 변경된 사용자만 업데이트
    private final Map<String, Map<String, Long>> previousRanks = new ConcurrentHashMap<>();
    
    public RealtimeStatsBroadcaster(AdmissionService admissionService, 
                                  WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
    }
    
    // ✅ 1초마다 통계 브로드캐스트 (빈도 증가)
    @Scheduled(fixedRate = 1000) 
    public void broadcastRealtimeStats() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            
            if (movieIds.isEmpty()) {
                return;
            }
            
            for (String movieId : movieIds) {
                try {
                    long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                    long activeCount = admissionService.getTotalActiveCount("movie", movieId);
                    
                    if (totalWaiting > 0 || activeCount > 0) {
                        webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
                        
                        logger.debug("📈 [실시간 통계] movieId={}, 대기={}명, 활성={}명", 
                                   movieId, totalWaiting, activeCount);
                    }
                } catch (Exception e) {
                    logger.error("❌ 영화 {} 통계 브로드캐스트 실패", movieId, e);
                }
            }
            
        } catch (Exception e) {
            logger.error("❌ 실시간 통계 브로드캐스트 전체 실패", e);
        }
    }
    
    // ✅ 2초마다 개별 순위 업데이트 (빈도 증가 + 최적화)
    @Scheduled(fixedRate = 2000)
    public void updateIndividualRanks() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            
            for (String movieId : movieIds) {
                try {
                    long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
                    if (totalWaiting == 0) {
                        // 대기자가 없으면 이전 캐시도 정리
                        previousRanks.remove(movieId);
                        continue;
                    }
                    
                    Map<String, Long> currentRanks = admissionService.getAllUserRanks("movie", movieId);
                    Map<String, Long> prevRanks = previousRanks.get(movieId);
                    
                    int updateCount = 0;
                    for (Map.Entry<String, Long> entry : currentRanks.entrySet()) {
                        try {
                            String requestId = entry.getKey();
                            Long currentRank = entry.getValue();
                            Long prevRank = prevRanks != null ? prevRanks.get(requestId) : null;
                            
                            // ✅ 순위가 변경된 경우만 업데이트
                            if (prevRank == null || !prevRank.equals(currentRank)) {
                                webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", currentRank, totalWaiting);
                                updateCount++;
                            }
                        } catch (Exception e) {
                            logger.error("❌ 개별 순위 업데이트 실패: requestId={}", entry.getKey(), e);
                        }
                    }
                    
                    // 현재 순위를 캐시에 저장
                    previousRanks.put(movieId, new ConcurrentHashMap<>(currentRanks));
                    
                    if (updateCount > 0) {
                        logger.debug("🔄 [개별 순위] movieId={}, {}명 중 {}명 순위 업데이트", 
                                   movieId, currentRanks.size(), updateCount);
                    }
                    
                } catch (Exception e) {
                    logger.error("❌ 영화 {} 개별 순위 업데이트 실패", movieId, e);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 개별 순위 업데이트 전체 실패", e);
        }
    }
    
    // ✅ 5초마다 전체 사용자에게 강제 순위 동기화
    @Scheduled(fixedRate = 5000)
    public void forceRankSync() {
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
                            logger.error("❌ 강제 순위 동기화 실패: requestId={}", entry.getKey(), e);
                        }
                    }
                    
                    logger.debug("🔄 [강제 동기화] movieId={}, {}명 순위 강제 업데이트", movieId, allRanks.size());
                    
                } catch (Exception e) {
                    logger.error("❌ 영화 {} 강제 순위 동기화 실패", movieId, e);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 강제 순위 동기화 전체 실패", e);
        }
    }
}