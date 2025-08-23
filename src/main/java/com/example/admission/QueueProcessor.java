package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    private final DynamicSessionCalculator sessionCalculator; // ★ 추가

    @Value("${admission.enable-proactive-admission:true}") // ★ 새로운 설정
    private boolean enableProactiveAdmission;

    public QueueProcessor(AdmissionService admissionService, 
                         WebSocketUpdateService webSocketUpdateService,
                         DynamicSessionCalculator sessionCalculator) { // ★ 추가
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
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
                // ★★★ 2단계 개선: 적극적인 빈 슬롯 활용 ★★★
                if (enableProactiveAdmission) {
                    processProactiveAdmission(type, movieId);
                }

                // 기존 기능: 대기자들에게 상태 업데이트
                updateWaitingUsersStatus(type, movieId);
                
                // 대기열이 비었으면 활성 큐 목록에서 제거
                admissionService.removeQueueIfEmpty(type, movieId);
            }
        } catch (Exception e) {
            logger.error("Queue processing 중 전체 오류 발생", e);
        }
    }

    /**
     * ★★★ 새로운 기능: 적극적인 빈 슬롯 활용 ★★★
     */
    private void processProactiveAdmission(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            if (vacantSlots <= 0 || waitingCount <= 0) {
                return;
            }

            // Pod 수에 비례한 배치 크기 계산
            long batchSize = calculateProactiveBatchSize(vacantSlots, waitingCount);
            
            if (batchSize <= 0) {
                return;
            }

            logger.info("[{}:{}] 적극적 입장 처리 - 빈 슬롯: {}, 대기자: {}, 처리할 인원: {}", 
                type, movieId, vacantSlots, waitingCount, batchSize);

            // 대기열에서 사용자들 추출하여 입장 처리
            Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, batchSize);

            if (!admittedUsers.isEmpty()) {
                for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                    String requestId = entry.getKey();
                    String sessionId = entry.getValue();
                    
                    admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                    webSocketUpdateService.notifyAdmitted(requestId);
                }

                logger.info("[{}:{}] 적극적 입장 처리 완료 - {}명 동시 입장", 
                    type, movieId, admittedUsers.size());
            }
        } catch (Exception e) {
            logger.error("[{}:{}] 적극적 입장 처리 중 오류", type, movieId, e);
        }
    }

    /**
     * ★★★ Pod 수 기반 적극적 배치 크기 계산 ★★★
     */
    private long calculateProactiveBatchSize(long vacantSlots, long waitingCount) {
        try {
            var calcInfo = sessionCalculator.getCalculationInfo();
            int podCount = calcInfo.currentPodCount();
            
            // Pod 수에 비례한 기본 배치 크기
            long baseBatchSize = Math.max(podCount / 2, 1); // Pod의 절반 정도
            
            // 안전한 범위 내에서 처리
            long safeBatchSize = Math.min(Math.min(vacantSlots, waitingCount), baseBatchSize);
            
            // 최대 5명까지만 (너무 많이 처리하지 않도록)
            return Math.min(safeBatchSize, 5);
            
        } catch (Exception e) {
            logger.error("적극적 배치 크기 계산 중 오류", e);
            return Math.min(vacantSlots, 1);
        }
    }

    /**
     * 대기자들에게 상태 업데이트 (기존 기능)
     */
    private void updateWaitingUsersStatus(String type, String movieId) {
        try {
            long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
            
            // 전체 대기자 수 브로드캐스트
            webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);

            // 각 대기자에게 자신의 현재 순위 전송
            updateWaitingUsersRank(type, movieId);
            
        } catch (Exception e) {
            logger.error("[{}:{}] 대기자 상태 업데이트 실패", type, movieId, e);
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