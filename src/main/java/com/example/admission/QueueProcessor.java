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
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;

    public QueueProcessor(AdmissionService admissionService, WebSocketUpdateService webSocketUpdateService) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
    }

    @Scheduled(fixedRate = 2000) // 2초마다 실행
    public void processQueues() {
        final String type = "movie";
        
        try {
            Set<String> activeMovieIds = admissionService.getActiveQueues(type);
            if (activeMovieIds == null || activeMovieIds.isEmpty()) {
                return;
            }

            for (String movieId : activeMovieIds) {
                // ★★★★★ 역할 축소 ★★★★★
                // 더 이상 빈자리를 확인하고 입장시키는 로직을 수행하지 않음.
                // SessionTimeoutProcessor와 신규 진입자가 이 역할을 담당.
                
                // 1. 남은 대기자들에게 상태 업데이트
                long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
                
                // 2. 전체 대기자 수 브로드캐스트
                webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);

                // 3. 각 대기자에게 자신의 현재 순위 전송
                updateWaitingUsersRank(type, movieId);
                
                // 4. 대기열이 비었으면 활성 큐 목록에서 제거
                admissionService.removeQueueIfEmpty(type, movieId);
            }
        } catch (Exception e) {
            logger.error("Queue processing 중 전체 오류 발생", e);
        }
    }
    
    private void updateWaitingUsersRank(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) return;

            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, rank);
            });
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
}