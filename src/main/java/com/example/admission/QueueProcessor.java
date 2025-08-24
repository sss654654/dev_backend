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

    @Scheduled(fixedDelayString = "${admission.queue-processor-interval-ms:2000}")
    public void processQueues() {
        long startTime = System.currentTimeMillis();
        Set<String> movieIds = admissionService.getActiveQueueMovieIds();
        if (movieIds.isEmpty()) {
            return;
        }

        logger.debug("대기열 처리 시작: {}개 영화", movieIds.size());

        for (String movieId : movieIds) {
            try {
                processQueueForMovie(movieId);
            } catch (Exception e) {
                logger.error("[{}] 대기열 처리 중 오류 발생", movieId, e);
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        logger.debug("전체 대기열 처리 완료. 소요시간: {}ms", duration);
    }
    
    private void processQueueForMovie(String movieId) {
        String type = "movie";

        // 1. 빈자리가 있는지 확인
        long vacantSlots = admissionService.getVacantSlots(type, movieId);
        if (vacantSlots <= 0) {
            // 빈자리가 없으면 대기열 상태만 업데이트하고 종료
            updateWaitingUsersStatus(type, movieId);
            return;
        }

        // 2. 대기자가 있는지 확인
        long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
        if (waitingCount <= 0) {
            // 대기자가 없으면 종료
            return;
        }

        // 3. 입장시킬 사용자 수 결정 (빈자리 수와 대기자 수 중 작은 값)
        long batchSize = Math.min(vacantSlots, waitingCount);
        
        // 4. Redis에서 다음 사용자들을 꺼내고 활성 세션으로 이동
        Map<String, String> admittedUsers = admissionService.admitUsersFromQueue(type, movieId, batchSize);
        if (admittedUsers.isEmpty()) {
            return;
        }
        
        logger.info("[{}] {}개의 빈자리가 있어 {}명을 입장시킵니다.", movieId, vacantSlots, admittedUsers.size());

        // 5. Kinesis를 통해 입장 허가 이벤트를 발행
        if (useKinesis) {
            logger.info("PRODUCER: Kinesis로 입장 이벤트 전송을 시작합니다.");
            kinesisProducer.publishBatchAdmitEvents(admittedUsers, movieId);
        } else {
            // Kinesis를 사용하지 않는 경우 (로컬 테스트 등), 직접 WebSocket으로 알림
            logger.warn("WARN: Kinesis 비활성화 상태. WebSocket으로 직접 알림을 전송합니다.");
            admittedUsers.keySet().forEach(webSocketUpdateService::notifyAdmitted);
        }
        
        // 6. 최신 대기열 상태를 모든 사용자에게 브로드캐스트
        updateWaitingUsersStatus(type, movieId);
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
            // 각 사용자에게 개별적으로 순위 전송
            userRanks.forEach(webSocketUpdateService::notifyRankUpdate);
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
}