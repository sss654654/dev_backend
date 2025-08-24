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
    private final DynamicSessionCalculator sessionCalculator;
    private final KinesisAdmissionProducer kinesisProducer;

    @Value("${admission.enable-proactive-admission:true}")
    private boolean enableProactiveAdmission;

    @Value("${admission.use-kinesis:true}")
    private boolean useKinesis;

    public QueueProcessor(AdmissionService admissionService, 
                          WebSocketUpdateService webSocketUpdateService,
                          DynamicSessionCalculator sessionCalculator,
                          KinesisAdmissionProducer kinesisProducer) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
        this.kinesisProducer = kinesisProducer;
    }

    /**
     * 시스템의 심장 역할.
     * 주기적으로 활성 대기열을 확인하고, 빈자리가 생겼다면 새로운 사용자를 입장시킨다.
     */
    @Scheduled(fixedRate = 2000) // 2초마다 실행
    public void processQueues() {
        if (!enableProactiveAdmission) {
            return;
        }
        
        final String type = "movie";
        
        try {
            Set<String> activeMovieIds = admissionService.getActiveQueues(type);
            if (activeMovieIds == null || activeMovieIds.isEmpty()) {
                return;
            }

            for (String movieId : activeMovieIds) {
                // ★★★ 이 클래스의 핵심 책임: 적극적으로 빈 슬롯을 찾아 입장 처리 ★★★
                processProactiveAdmission(type, movieId);

                // 대기자들에게 상태 업데이트
                updateWaitingUsersStatus(type, movieId);
                
                // 대기열이 비었으면 활성 큐 목록에서 제거
                admissionService.removeQueueIfEmpty(type, movieId);
            }
        } catch (Exception e) {
            logger.error("Queue processing 중 전체 오류 발생", e);
        }
    }

    /**
     * Kinesis Producer와 연동하여 새로운 사용자를 입장시키는 로직
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

            // 대기열에서 사용자들 추출
            Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, batchSize);

            if (!admittedUsers.isEmpty()) {
                for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                    String requestId = entry.getKey();
                    String sessionId = entry.getValue();
                    
                    // 1. Redis 활성 세션에 추가
                    admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                    
                    // 2. Kinesis 사용 여부에 따라 분기하여 알림 전송
                    if (useKinesis) {
                        // Kinesis Producer로 이벤트 전송 (Consumer가 받아서 WebSocket 처리)
                        kinesisProducer.publishAdmitEvent(requestId, movieId, sessionId);
                        logger.info("PROCESSOR: Kinesis 이벤트 전송 -> requestId: {}", requestId);
                    } else {
                        // 기존 방식: 직접 WebSocket 전송 (fallback)
                        webSocketUpdateService.notifyAdmitted(requestId);
                        logger.warn("PROCESSOR: Kinesis 비활성화, 직접 WebSocket 전송 -> requestId: {}", requestId);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[{}:{}] 적극적 입장 처리 중 오류", type, movieId, e);
        }
    }
    
    // (calculateProactiveBatchSize, updateWaitingUsersStatus, updateWaitingUsersRank 메서드는 변경 없음)
    
    private long calculateProactiveBatchSize(long vacantSlots, long waitingCount) {
        try {
            var calcInfo = sessionCalculator.getCalculationInfo();
            int podCount = calcInfo.currentPodCount();
            long baseBatchSize = Math.max(podCount / 2, 1);
            long safeBatchSize = Math.min(Math.min(vacantSlots, waitingCount), baseBatchSize);
            return Math.min(safeBatchSize, 5);
        } catch (Exception e) {
            logger.error("적극적 배치 크기 계산 중 오류", e);
            return Math.min(vacantSlots, 1);
        }
    }
    
    private void updateWaitingUsersStatus(String type, String movieId) {
        try {
            long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
            webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
            updateWaitingUsersRank(type, movieId);
        } catch (Exception e) {
            logger.error("[{}:{}] 대기자 상태 업데이트 실패", type, movieId, e);
        }
    }
    
    private void updateWaitingUsersRank(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) return;
            userRanks.forEach(webSocketUpdateService::notifyRankUpdate);
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
}