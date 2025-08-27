// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.service.LoadBalancingOptimizer;  // ✅ 올바른 패키지로 수정
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    private final DynamicSessionCalculator sessionCalculator;
    private final LoadBalancingOptimizer loadBalancingOptimizer;

    @Autowired
    public QueueProcessor(AdmissionService admissionService, 
                         WebSocketUpdateService webSocketUpdateService, 
                         DynamicSessionCalculator sessionCalculator,
                         LoadBalancingOptimizer loadBalancingOptimizer) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
    }

    // ✅ 수정: 더 자주 실행하여 빠른 처리 (1초마다) + 예외 처리 강화
    @Scheduled(fixedRateString = "${queueProcessorInterval:1000}")
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) return;
            
            logger.debug("🔄 대기열 처리 시작 - 활성 영화 {}개", movieIds.size());
            
            for (String movieId : movieIds) {
                try {
                    // ✅ 부하 분산 로직 추가
                    if (!loadBalancingOptimizer.shouldProcessMovie(movieId)) {
                        logger.debug("🔀 [{}] 부하 분산으로 인해 다른 Pod에서 처리", movieId);
                        continue;
                    }
                    
                    processMovieQueue("movie", movieId);
                } catch (Exception e) {
                    logger.error("❌ [{}] 대기열 처리 실패", movieId, e);
                    
                    // ✅ Redis WRONGTYPE 오류 특별 처리
                    if (isRedisTypeError(e)) {
                        logger.warn("🔧 [{}] Redis 타입 오류 감지. 다음 주기에서 재시도", movieId);
                        // 다음 처리 주기에서 자동으로 키가 재생성됨
                    }
                }
            }
        } catch (Exception e) {
            logger.error("❌ 대기열 처리 중 전체 오류 발생", e);
        }
    }

    private void processMovieQueue(String type, String movieId) {
        try {
            // 1. 빈자리 확인 (방어적 조회)
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            long activeCount = admissionService.getTotalActiveCount(type, movieId);
            
            logger.debug("📊 [{}] 현황 - 활성: {}, 대기: {}, 빈자리: {}", 
                        movieId, activeCount, waitingCount, vacantSlots);
            
            // 2. 빈자리가 있고 대기자가 있으면 처리
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                
                // ✅ 핵심: 대기자들을 활성 세션으로 승격 (방어 로직 포함)
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    logger.info("🚀 [{}] {}명을 대기열에서 활성 세션으로 승격", movieId, admittedUsers.size());
                    
                    // ✅ 중요: 승격된 사용자들에게 입장 허가 WebSocket 알림 전송
                    for (String member : admittedUsers) {
                        try {
                            String[] parts = member.split(":", 2);
                            if (parts.length >= 1) {
                                String requestId = parts[0];
                                
                                // 🎯 핵심: WebSocket으로 입장 허가 알림 → WaitPage에서 SeatsPage로 이동
                                webSocketUpdateService.notifyAdmission(requestId, movieId);
                                
                                logger.info("✅ [{}] 입장 허가 알림 전송 완료 - requestId: {}...", 
                                           movieId, requestId.substring(0, 8));
                            }
                        } catch (Exception e) {
                            logger.error("❌ [{}] 입장 알림 전송 실패 - member: {}", movieId, member, e);
                        }
                    }
                } else {
                    logger.debug("🔍 [{}] 승격할 사용자가 없음 (Redis 오류 복구 중일 수 있음)", movieId);
                }
            }

            // 3. ✅ 만료된 활성 세션 정리 (방어 로직 포함)
            try {
                Set<String> expiredSessions = admissionService.findExpiredActiveSessions(type, movieId);
                if (expiredSessions != null && !expiredSessions.isEmpty()) {
                    admissionService.removeActiveSessions(type, movieId, expiredSessions);
                    
                    // 만료 세션 정리 후 추가 승격 기회 제공
                    long newVacantSlots = admissionService.getVacantSlots(type, movieId);
                    long newWaitingCount = admissionService.getTotalWaitingCount(type, movieId);
                    
                    if (newVacantSlots > 0 && newWaitingCount > 0) {
                        logger.debug("🔄 [{}] 만료 세션 정리 후 추가 승격 시도 - 빈자리: {}, 대기: {}", 
                                   movieId, newVacantSlots, newWaitingCount);
                        
                        long additionalAdmits = Math.min(newVacantSlots, newWaitingCount);
                        List<String> additionalUsers = admissionService.admitNextUsers(type, movieId, additionalAdmits);
                        
                        for (String member : additionalUsers) {
                            try {
                                String requestId = member.split(":")[0];
                                webSocketUpdateService.notifyAdmission(requestId, movieId);
                                logger.info("✅ [{}] 추가 승격 알림 전송 - requestId: {}...", 
                                          movieId, requestId.substring(0, 8));
                            } catch (Exception e) {
                                logger.error("❌ [{}] 추가 승격 알림 실패 - member: {}", movieId, member, e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("⚠️ [{}] 만료 세션 정리 중 오류 (무시하고 계속)", movieId, e);
            }

        } catch (Exception e) {
            logger.error("❌ [{}] 영화 대기열 처리 중 오류", movieId, e);
            
            if (isRedisTypeError(e)) {
                logger.warn("🔧 [{}] Redis 타입 오류 감지. AdmissionService에서 키 정리 중", movieId);
                // AdmissionService의 방어 로직이 키를 정리하므로 별도 처리 불필요
            }
        }
    }

    // ✅ Redis WRONGTYPE 오류 판별 유틸리티
    private boolean isRedisTypeError(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("WRONGTYPE")) {
            return true;
        }
        
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("WRONGTYPE")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // ✅ 추가: 시스템 상태 모니터링 (선택사항)
    @Scheduled(fixedDelayString = "${systemStatusLogInterval:300000}") // 5분마다
    public void logSystemStatus() {
        try {
            Set<String> allMovies = admissionService.getActiveQueueMovieIds();
            if (allMovies.isEmpty()) {
                logger.info("📊 시스템 상태: 활성 대기열 없음");
                return;
            }

            long totalActive = 0;
            long totalWaiting = 0;
            int movieCount = 0;

            for (String movieId : allMovies) {
                try {
                    long active = admissionService.getTotalActiveCount("movie", movieId);
                    long waiting = admissionService.getTotalWaitingCount("movie", movieId);
                    
                    totalActive += active;
                    totalWaiting += waiting;
                    movieCount++;
                    
                    if (active > 0 || waiting > 0) {
                        logger.info("📊 [{}] 활성: {}, 대기: {}", movieId, active, waiting);
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ [{}] 상태 조회 실패", movieId, e);
                }
            }

            long maxSessions = sessionCalculator.calculateMaxActiveSessions();
            logger.info("📊 전체 시스템 상태 - 영화: {}개, 총 활성: {}/{}, 총 대기: {}", 
                       movieCount, totalActive, maxSessions, totalWaiting);

        } catch (Exception e) {
            logger.error("❌ 시스템 상태 로깅 실패", e);
        }
    }
}