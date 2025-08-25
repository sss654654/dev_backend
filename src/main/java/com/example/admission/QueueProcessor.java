package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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

    @Scheduled(fixedRate = 5000)
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) {
                return;
            }
            for (String movieId : movieIds) {
                processMovieQueue("movie", movieId);
            }
        } catch (Exception e) {
            logger.error("대기열 처리 중 심각한 오류 발생", e);
        }
    }
    
    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            if (vacantSlots <= 0) {
                return;
            }
            
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            if (waitingCount == 0) {
                return;
            }
            
            long admitCount = Math.min(vacantSlots, waitingCount);
            logger.info("[{}] 빈자리 {}개 발견! {}명 입장 처리 시작...", movieId, vacantSlots, admitCount);
            
            List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);

            if (useKinesis) {
                // ✨ 수정된 부분: 이제 KinesisProducer에 새로 추가된 배치 메소드를 정상적으로 호출합니다.
                kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
            } else {
                // Kinesis를 사용하지 않을 경우의 대체 로직
                admittedUsers.forEach(member -> {
                    String requestId = member.split(":")[0];
                    webSocketUpdateService.notifyAdmitted(requestId);
                });
            }
            
            updateWaitingRanks(type, movieId);

        } catch (Exception e) {
            logger.error("[{}] 대기열 처리 실패", movieId, e);
        }
    }

    private void updateWaitingRanks(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) {
                logger.debug("[{}] 대기 중인 사용자 없음", movieId);
                return;
            }

            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, rank);
            });
            
            logger.debug("[{}] 순위 업데이트 전송 완료 - {} 명", movieId, userRanks.size());
            
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void logSystemStatus() {
        try {
            Set<String> activeMovies = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            int activeCount = activeMovies != null ? activeMovies.size() : 0;
            int waitingCount = waitingMovies != null ? waitingMovies.size() : 0;
            
            logger.info("📊 시스템 상태 - 활성 영화: {}개, 대기열 있는 영화: {}개, Kinesis 사용: {}", 
                       activeCount, waitingCount, useKinesis);

        } catch (Exception e) {
            logger.error("시스템 상태 로깅 중 오류", e);
        }
    }
} 
// .