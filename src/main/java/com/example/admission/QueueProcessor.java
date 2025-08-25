package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final KinesisAdmissionProducer kinesisProducer;
    private final WebSocketUpdateService webSocketUpdateService;
    private final RedisTemplate<String, String> redisTemplate;
    
    @Value("${admission.use-kinesis:true}")
    private boolean useKinesis;
    
    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    public QueueProcessor(AdmissionService admissionService,
                         KinesisAdmissionProducer kinesisProducer,
                         WebSocketUpdateService webSocketUpdateService,
                         RedisTemplate<String, String> redisTemplate) {
        this.admissionService = admissionService;
        this.kinesisProducer = kinesisProducer;
        this.webSocketUpdateService = webSocketUpdateService;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 🔹 핵심 개선: 5초마다 정확한 세션 상태 확인 후 대기열 처리
     */
    @Scheduled(fixedDelay = 5000)
    public void processWaitingQueues() {
        long startTime = System.currentTimeMillis();
        
        try {
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            if (waitingMovies == null || waitingMovies.isEmpty()) {
                logger.debug("대기 중인 영화 없음");
                return;
            }

            logger.info("=== 대기열 처리 시작 - {} 개 영화 확인 ===", waitingMovies.size());
            
            for (String movieId : waitingMovies) {
                processQueueForMovie(movieId);
            }
            
        } catch (Exception e) {
            logger.error("대기열 처리 중 오류 발생", e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logger.info("대기열 처리 완료 - 소요시간: {}ms", duration);
        }
    }

    /**
     * 🔹 핵심 개선: 영화별 정확한 빈자리 확인 후 입장 처리
     */
    private void processQueueForMovie(String movieId) {
        String type = "movie";

        // 1. 정확한 빈자리 수 확인 (만료된 세션 자동 제거 포함)
        long vacantSlots = admissionService.getVacantSlots(type, movieId);
        long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
        long currentActive = admissionService.getActiveSessionCount(type, movieId);
        
        logger.debug("[{}] 상태 확인 - 빈자리: {}, 대기자: {}, 활성세션: {}", 
                    movieId, vacantSlots, totalWaiting, currentActive);

        if (vacantSlots <= 0) {
            logger.debug("[{}] 빈자리 없음 - 순위 업데이트만 수행", movieId);
            updateWaitingUsersStatus(type, movieId);
            return;
        }

        if (totalWaiting <= 0) {
            logger.debug("[{}] 대기자 없음", movieId);
            return;
        }

        // 2. 배치 크기 결정 (빈자리와 대기자 수 중 작은 값)
        long batchSize = Math.min(vacantSlots, totalWaiting);

        // 3. 대기열에서 사용자 입장 처리
        Map<String, String> admittedUsers = admissionService.admitUsersFromQueue(type, movieId, batchSize);
        if (admittedUsers.isEmpty()) {
            logger.debug("[{}] 입장 처리된 사용자 없음", movieId);
            return;
        }

        logger.info("[{}] 🎬 입장 처리 완료 - {}개 빈자리에 {}명 입장 승인", 
                   movieId, vacantSlots, admittedUsers.size());

        // 4. 🚨 핵심 개선: Kinesis vs WebSocket 분기 처리
        if (useKinesis) {
            logger.info("PRODUCER: Kinesis로 입장 이벤트 전송 시작 - {} 명", admittedUsers.size());
            kinesisProducer.publishBatchAdmitEvents(admittedUsers, movieId);
        } else {
            logger.warn("KINESIS 비활성화: WebSocket 직접 전송 - {} 명", admittedUsers.size());
            admittedUsers.keySet().forEach(requestId -> {
                webSocketUpdateService.notifyAdmitted(requestId);
                logger.debug("WebSocket 입장 알림 전송: {}", requestId);
            });
        }

        // 5. 대기열 상태 업데이트
        updateWaitingUsersStatus(type, movieId);
    }

    /**
     * 🔹 대기 중인 사용자들에게 상태 업데이트 전송
     */
    private void updateWaitingUsersStatus(String type, String movieId) {
        try {
            long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
            long currentActive = admissionService.getActiveSessionCount(type, movieId);
            
            // 전체 대기열 통계 브로드캐스트
            webSocketUpdateService.broadcastQueueStats(movieId, totalWaiting);
            
            // 개별 사용자 순위 업데이트
            updateWaitingUsersRank(type, movieId);
            
            logger.debug("[{}] 상태 업데이트 완료 - 활성: {}, 대기: {}", 
                        movieId, currentActive, totalWaiting);
            
        } catch (Exception e) {
            logger.error("[{}:{}] 대기자 상태 업데이트 실패", type, movieId, e);
        }
    }

    /**
     * 🔹 개별 사용자 순위 업데이트 (WebSocket 개인 메시지)
     */
    private void updateWaitingUsersRank(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) {
                logger.debug("[{}] 대기 중인 사용자 없음", movieId);
                return;
            }

            // 각 사용자에게 개별 순위 알림 전송
            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, rank);
            });
            
            logger.debug("[{}] 순위 업데이트 전송 완료 - {} 명", movieId, userRanks.size());
            
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }

    /**
     * 🔹 시스템 상태 모니터링 (1분마다)
     */
    @Scheduled(fixedRate = 60000)
    public void logSystemStatus() {
        try {
            Set<String> activeMovies = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            
            int activeCount = activeMovies != null ? activeMovies.size() : 0;
            int waitingCount = waitingMovies != null ? waitingMovies.size() : 0;
            
            logger.info("📊 시스템 상태 - 활성 영화: {}개, 대기열 있는 영화: {}개, Kinesis 사용: {}", 
                       activeCount, waitingCount, useKinesis ? "ON" : "OFF");
                       
        } catch (Exception e) {
            logger.error("시스템 상태 로깅 실패", e);
        }
    }
}