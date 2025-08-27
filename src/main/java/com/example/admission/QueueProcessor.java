// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.service.LoadBalancingOptimizer;
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
    private final DynamicSessionCalculator sessionCalculator;
    private final LoadBalancingOptimizer loadBalancingOptimizer;
    private final KinesisAdmissionProducer kinesisAdmissionProducer; // WebSocketUpdateService 대신 Kinesis Producer 주입

    @Autowired
    public QueueProcessor(AdmissionService admissionService, 
                         DynamicSessionCalculator sessionCalculator,
                         LoadBalancingOptimizer loadBalancingOptimizer,
                         KinesisAdmissionProducer kinesisAdmissionProducer) { // 생성자 수정
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
        this.kinesisAdmissionProducer = kinesisAdmissionProducer; // 주입
    }

    @Scheduled(fixedRateString = "${queueProcessorInterval:1000}")
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) return;
            
            logger.debug("🔄 대기열 처리 시작 - 활성 영화 {}개", movieIds.size());
            
            for (String movieId : movieIds) {
                try {
                    if (!loadBalancingOptimizer.shouldProcessMovie(movieId)) {
                        logger.debug("🔀 [{}] 부하 분산으로 인해 다른 Pod에서 처리", movieId);
                        continue;
                    }
                    
                    processMovieQueue("movie", movieId);
                } catch (Exception e) {
                    logger.error("❌ [{}] 대기열 처리 실패", movieId, e);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 대기열 처리 중 전체 오류 발생", e);
        }
    }

    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    logger.info("🚀 [{}] {}명을 대기열에서 활성 세션으로 승격", movieId, admittedUsers.size());
                    
                    // ⭐⭐⭐ [핵심 수정] WebSocket 직접 호출 대신 Kinesis로 이벤트 발행 ⭐⭐⭐
                    kinesisAdmissionProducer.publishAdmitEvents(admittedUsers, movieId);
                }
            }

        } catch (Exception e) {
            logger.error("❌ [{}] 영화 대기열 처리 중 오류", movieId, e);
        }
    }
    
    // isRedisTypeError, logSystemStatus 메서드는 기존과 동일하게 유지
    private boolean isRedisTypeError(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("WRONGTYPE")) return true;
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("WRONGTYPE")) return true;
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