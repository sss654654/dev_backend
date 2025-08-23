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
                processMovieQueue(type, movieId);
            }
        } catch (Exception e) {
            logger.error("Queue processing 중 전체 오류 발생", e);
        }
    }
    
    private void processMovieQueue(String type, String movieId) {
        try {
            // 1. 빈자리 확인
            long vacantSlots = admissionService.getVacantSlots(type, movieId);

            if (vacantSlots > 0) {
                // 2. 빈자리만큼 대기열에서 사용자 추출
                Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, vacantSlots);

                if (!admittedUsers.isEmpty()) {
                    logger.info("[{}:{}] 처리 시작: {}명 입장", type, movieId, admittedUsers.size());
                    
                    for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                        String requestId = entry.getKey();
                        String sessionId = entry.getValue();
                        
                        // 3. 활성 세션으로 이동
                        admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                        
                        // ★ 문제 2 해결: WebSocket으로 직접 입장 허가 알림 전송
                        webSocketUpdateService.notifyAdmitted(requestId);
                    }
                }
            }

            // 4. 남은 대기자들에게 상태 업데이트
            long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
            
            // ★ 전체 대기자 수 브로드캐스트
            webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);

            // ★ 문제 1 해결: 각 대기자에게 자신의 현재 순위 전송
            updateWaitingUsersRank(type, movieId);
            
            // 5. 대기열이 비었으면 활성 큐 목록에서 제거
            admissionService.removeQueueIfEmpty(type, movieId);
            
        } catch (Exception e) {
            logger.error("Movie queue processing 중 오류: movieId={}", movieId, e);
        }
    }
    
    private void updateWaitingUsersRank(String type, String movieId) {
        try {
            // Redis에서 requestId를 키로, 순위를 값으로 하는 맵을 가져옴
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) return;

            // 각 사용자에게 순위 정보 전송
            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, rank);
            });
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
}