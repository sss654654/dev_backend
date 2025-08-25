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

    /**
     * 5초마다 대기열 처리 (빈 자리가 생기면 대기자들을 활성세션으로 승격)
     */
    @Scheduled(fixedRate = 5000)
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) {
                return;
            }
            
            logger.debug("대기열 처리 시작 - {} 개 영화 확인", movieIds.size());
            
            for (String movieId : movieIds) {
                processMovieQueue("movie", movieId);
            }
        } catch (Exception e) {
            logger.error("대기열 처리 중 심각한 오류 발생", e);
        }
    }
    
    /**
     * 특정 영화의 대기열 처리
     */
    private void processMovieQueue(String type, String movieId) {
        try {
            // 빈 자리 계산
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            if (vacantSlots <= 0) {
                return; // 빈 자리가 없으면 처리하지 않음
            }
            
            // 대기자 수 확인
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            if (waitingCount == 0) {
                return; // 대기자가 없으면 처리하지 않음
            }
            
            // 실제 승격할 인원 계산
            long admitCount = Math.min(vacantSlots, waitingCount);
            logger.info("[{}] 빈자리 {}개 발견! {}명 입장 처리 시작...", movieId, vacantSlots, admitCount);
            
            // 대기열에서 활성세션으로 승격
            List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);

            if (!admittedUsers.isEmpty()) {
                if (useKinesis) {
                    // 키네시스로 이벤트 발송 (배치)
                    sendAdmissionEvents(movieId, admittedUsers);
                } else {
                    // 키네시스 사용 안 하면 직접 웹소켓 발송
                    admittedUsers.forEach(member -> {
                        String requestId = member.split(":")[0];
                        webSocketUpdateService.notifyAdmitted(requestId);
                    });
                }
                
                // 남은 대기자들에게 순위 업데이트 알림
                updateWaitingRanks(type, movieId);
                
                // 전체 통계 브로드캐스트
                long remainingWaiting = admissionService.getTotalWaitingCount(type, movieId);
                webSocketUpdateService.broadcastQueueStats(movieId, remainingWaiting);
                
                logger.info("[{}] 대기열 처리 완료 - {}명 승격, 남은 대기자: {}명", 
                           movieId, admittedUsers.size(), remainingWaiting);
            }

        } catch (Exception e) {
            logger.error("[{}] 대기열 처리 실패", movieId, e);
        }
    }

    /**
     * 키네시스로 입장 허가 이벤트들을 발송
     */
    private void sendAdmissionEvents(String movieId, List<String> admittedUsers) {
        try {
            logger.info("[{}] 키네시스 이벤트 배치 발송 시작 - 대상: {}명", movieId, admittedUsers.size());
            
            // 각 사용자에게 개별 이벤트 발송
            for (String member : admittedUsers) {
                try {
                    String[] parts = member.split(":", 2);
                    if (parts.length >= 1) {
                        String requestId = parts[0];
                        
                        // KinesisAdmissionProducer의 메서드 호출 (기존 코드 구조 유지)
                        kinesisProducer.sendAdmissionEvent(requestId, movieId);
                        
                        logger.debug("[{}] 키네시스 이벤트 발송: {}", movieId, requestId);
                    }
                } catch (Exception e) {
                    logger.error("[{}] 개별 키네시스 이벤트 발송 실패: {}", movieId, member, e);
                }
            }
            
            logger.info("[{}] 키네시스 이벤트 배치 발송 완료: {}건", movieId, admittedUsers.size());
            
        } catch (Exception e) {
            logger.error("[{}] 키네시스 배치 발송 중 오류 발생", movieId, e);
        }
    }

    /**
     * 남은 대기자들에게 업데이트된 순위 알림
     */
    private void updateWaitingRanks(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            if (userRanks.isEmpty()) {
                logger.debug("[{}] 대기 중인 사용자 없음", movieId);
                return;
            }

            // 각 대기자에게 새로운 순위 알림
            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, rank);
            });
            
            logger.debug("[{}] 순위 업데이트 전송 완료 - {} 명", movieId, userRanks.size());
            
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }

    /**
     * 시스템 상태 로깅 (1분마다)
     */
    @Scheduled(fixedRate = 60000)
    public void logSystemStatus() {
        try {
            Set<String> activeMovies = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            int activeCount = activeMovies != null ? activeMovies.size() : 0;
            int waitingCount = waitingMovies != null ? waitingMovies.size() : 0;
            
            logger.info("시스템 상태 - 활성 영화: {}개, 대기열 있는 영화: {}개, Kinesis 사용: {}", 
                       activeCount, waitingCount, useKinesis);

        } catch (Exception e) {
            logger.error("시스템 상태 로깅 중 오류", e);
        }
    }
}