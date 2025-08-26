package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
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
    private final DynamicSessionCalculator sessionCalculator;
    
    @Value("${admission.use-kinesis:true}")
    private boolean useKinesis;
    
    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    public QueueProcessor(AdmissionService admissionService,
                         KinesisAdmissionProducer kinesisProducer,
                         WebSocketUpdateService webSocketUpdateService,
                         RedisTemplate<String, String> redisTemplate,
                         DynamicSessionCalculator sessionCalculator) {
        this.admissionService = admissionService;
        this.kinesisProducer = kinesisProducer;
        this.webSocketUpdateService = webSocketUpdateService;
        this.redisTemplate = redisTemplate;
        this.sessionCalculator = sessionCalculator;
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

    // 핵심 수정: Kinesis + WebSocket 이중 처리
    private void processMovieQueue(String type, String movieId) {
        try {
            long maxSessions = sessionCalculator.calculateMaxActiveSessions();
            long currentSessions = admissionService.getTotalActiveCount(type, movieId);
            long vacantSlots = maxSessions - currentSessions;
            
            if (vacantSlots <= 0) {
                // 빈자리가 없어도 순위 업데이트는 해야 함
                updateWaitingRanks(type, movieId);
                return;
            }
            
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            if (waitingCount == 0) {
                return;
            }
            
            long admitCount = Math.min(vacantSlots, waitingCount);
            logger.info("[{}] {}명 빈자리 → {}명 입장 처리 시작...", movieId, vacantSlots, admitCount);
            
            // 입장 처리
            List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);

            // 핵심 수정: Kinesis와 WebSocket 이중 처리로 안전성 보장
            if (!admittedUsers.isEmpty()) {
                
                // 1️⃣ 즉시 WebSocket 알림 전송 (백업용, 실시간 보장)
                admittedUsers.forEach(member -> {
                    String requestId = member.split(":")[0];
                    webSocketUpdateService.notifyAdmission(requestId, movieId);
                    logger.info("🎬 DIRECT WebSocket: 입장 허가 알림 즉시 전송 - requestId: {}...", 
                               requestId.substring(0, Math.min(8, requestId.length())));
                });
                
                // 2️⃣ Kinesis로도 전송 (신뢰성 보장, 로그 기록)
                if (useKinesis) {
                    try {
                        kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                        logger.info("📡 KINESIS: 입장 허가 이벤트 전송 완료 - {} 명", admittedUsers.size());
                    } catch (Exception e) {
                        logger.error("📡 KINESIS: 전송 실패, WebSocket으로 이미 처리됨", e);
                    }
                }
            }
            
            // 입장 처리 후 즉시 순위 업데이트 (남은 사용자들)
            updateWaitingRanks(type, movieId);
            
            // 전체 통계 브로드캐스트 (입장 처리 후)
            long remainingWaiting = admissionService.getTotalWaitingCount(type, movieId);
            webSocketUpdateService.broadcastQueueStats(movieId, remainingWaiting);

        } catch (Exception e) {
            logger.error("[{}] 대기열 처리 실패", movieId, e);
        }
    }

    private void updateWaitingRanks(String type, String movieId) {
        try {
            Map<String, Long> userRanks = admissionService.getAllUserRanks(type, movieId);
            long totalWaiting = admissionService.getTotalWaitingCount(type, movieId);
            
            // 각 사용자에게 개별 순위 업데이트 전송
            userRanks.forEach((requestId, rank) -> {
                webSocketUpdateService.notifyRankUpdate(requestId, "WAITING", rank, totalWaiting);
                
                // 디버그 로그 추가
                logger.debug("[{}] 순위 업데이트 전송 - requestId: {}..., rank: {}/{}", 
                            movieId, requestId.substring(0, 8), rank, totalWaiting);
            });
            
            // 순위 업데이트 완료 로그
            if (!userRanks.isEmpty()) {
                logger.info("[{}] 순위 업데이트 완료 - {}명에게 전송", movieId, userRanks.size());
            }
            
        } catch (Exception e) {
            logger.error("대기 순위 업데이트 실패: movieId={}", movieId, e);
        }
    }
    
    public void processUserAdmission(String type, String movieId, String requestId) {
        try {
            // 기존 admitNextUsers 메서드를 1명 단위로 호출
            List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, 1);
            
            if (!admittedUsers.isEmpty()) {
                String admittedMember = admittedUsers.get(0);
                String admittedRequestId = admittedMember.split(":")[0];
                
                // 요청한 사용자가 입장되었는지 확인
                if (requestId.equals(admittedRequestId)) {
                    // 즉시 WebSocket 알림
                    webSocketUpdateService.notifyAdmission(requestId, movieId);
                    logger.info("🎬 DIRECT WebSocket: 개별 입장 허가 알림 - requestId: {}...", 
                               requestId.substring(0, 8));
                    
                    // Kinesis로도 전송
                    if (useKinesis) {
                        try {
                            kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                            logger.info("📡 KINESIS: 개별 입장 이벤트 전송 완료");
                        } catch (Exception e) {
                            logger.error("📡 KINESIS: 개별 전송 실패, WebSocket으로 이미 처리됨", e);
                        }
                    }
                    
                    logger.info("[{}] 개별 사용자 입장 처리 완료 - requestId: {}", movieId, requestId);
                } else {
                    logger.warn("[{}] 요청한 사용자가 아닌 다른 사용자가 입장됨 - 요청: {}, 입장: {}", 
                            movieId, requestId, admittedRequestId);
                }
                
                // 나머지 사용자들 순위 업데이트
                updateWaitingRanks(type, movieId);
            } else {
                logger.warn("[{}] 개별 입장 처리 실패 - 빈자리 없음 또는 대기자 없음", movieId);
            }
            
        } catch (Exception e) {
            logger.error("개별 사용자 입장 처리 실패 - requestId: {}, movieId: {}", requestId, movieId, e);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void logSystemStatus() {
        try {
            Set<String> activeMovies = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            int activeCount = activeMovies != null ? activeMovies.size() : 0;
            int waitingCount = waitingMovies != null ? waitingMovies.size() : 0;
            
            logger.info("📊 시스템 상태 - 활성 영화: {}개, 대기열 있는 영화: {}개, Kinesis 사용: {}, 이중처리: ON", 
                       activeCount, waitingCount, useKinesis);

        } catch (Exception e) {
            logger.error("시스템 상태 로깅 중 오류", e);
        }
    }
}