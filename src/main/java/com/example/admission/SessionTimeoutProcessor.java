package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.nio.charset.StandardCharsets;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    
    private final StringRedisTemplate redisTemplate;
    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    private final DynamicSessionCalculator sessionCalculator;
    private final AdmissionMetricsService metricsService; // ★ 3단계 추가
    private final LoadBalancingOptimizer loadBalancer;    // ★ 3단계 추가

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    @Value("${admission.aggressive-batch-processing:true}")
    private boolean aggressiveBatchProcessing;

    public SessionTimeoutProcessor(StringRedisTemplate redisTemplate, 
                                 AdmissionService admissionService, 
                                 WebSocketUpdateService webSocketUpdateService,
                                 DynamicSessionCalculator sessionCalculator,
                                 AdmissionMetricsService metricsService,  // ★ 3단계 추가
                                 LoadBalancingOptimizer loadBalancer) {   // ★ 3단계 추가
        this.redisTemplate = redisTemplate;
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.sessionCalculator = sessionCalculator;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    @Scheduled(fixedRate = 2000)
    public void processExpiredSessionsAndAdmitNext() {
        if (sessionTimeoutSeconds <= 0) return;
        long startTime = System.currentTimeMillis();
        long expirationTime = startTime - (sessionTimeoutSeconds * 1000);
        ScanOptions options = ScanOptions.scanOptions()
                .match("active_sessions:movie:*")
                .count(100)
                .build();
        int totalProcessedMovies = 0;
        int totalTimeouts = 0;
        int totalAdmissions = 0;
        
        // ✅ 수정: ConnectionFactory는 try-with-resources 밖으로
        var connFactory = redisTemplate.getConnectionFactory();
        if (connFactory == null) {
            logger.warn("Redis 연결 팩토리를 가져올 수 없어 스캔을 건너뜁니다.");
            return;
        }
        
        try (var conn = connFactory.getConnection();
            Cursor<byte[]> cursor = conn.scan(options)) {
            
            while (cursor.hasNext()) {
                String activeSessionsKey = new String(cursor.next(), StandardCharsets.UTF_8);
                String movieId = extractMovieId(activeSessionsKey);
                if (movieId == null) continue;
                
                // 부하 분산 확인
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    logger.debug("영화 {} 처리를 다른 Pod에 위임", movieId);
                    continue;
                }
                
                totalProcessedMovies++;
                
                // 만료된 세션 처리
                Set<String> expiredMembers =
                        redisTemplate.opsForZSet().rangeByScore(activeSessionsKey, 0, expirationTime);
                if (expiredMembers != null && !expiredMembers.isEmpty()) {
                    redisTemplate.opsForZSet().removeRangeByScore(activeSessionsKey, 0, expirationTime);
                    int expiredCount = expiredMembers.size();
                    totalTimeouts += expiredCount;
                    logger.info("[{}] 만료된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, expiredCount);
                    
                    // 타임아웃 알림 전송
                    expiredMembers.forEach(member -> {
                        if (member.contains(":")) {
                            String requestId = member.split(":", 2)[0];
                            webSocketUpdateService.notifyTimeout(requestId);
                        }
                    });
                    
                    // 메트릭 기록
                    metricsService.recordTimeout(movieId, expiredCount);
                    
                    // 최적화된 배치 입장 처리
                    int admittedCount = admitNextUsersOptimized(movieId, expiredCount);
                    totalAdmissions += admittedCount;
                }
            }
        } catch (Exception e) {
            logger.error("만료된 세션 정리 및 다음 사용자 입장 처리 중 오류 발생", e);
        }
        
        // Pod 부하 업데이트 & 성능 메트릭 기록
        long processingTime = System.currentTimeMillis() - startTime;
        if (totalProcessedMovies > 0) {
            loadBalancer.updatePodLoad(totalProcessedMovies);
            if (totalAdmissions > 0) {
                metricsService.recordEntry("batch", totalAdmissions, processingTime);
            }
            logger.debug("배치 처리 완료 - 처리 영화: {}, 타임아웃: {}, 입장: {}, 소요시간: {}ms",
                    totalProcessedMovies, totalTimeouts, totalAdmissions, processingTime);
        }
    }

    /**
     * ★★★ 3단계 개선: 성능 메트릭 통합된 최적화된 대량 입장 처리 ★★★
     */
    private int admitNextUsersOptimized(String movieId, int expiredCount) {
        final String type = "movie";
        long processingStartTime = System.currentTimeMillis();
        
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            
            if (vacantSlots <= 0) {
                logger.debug("[{}:{}] 빈 슬롯이 없어 입장 처리를 건너뜁니다.", type, movieId);
                return 0;
            }

            // ★★★ 3단계: 고급 배치 크기 계산 (성능 분석 기반) ★★★
            long batchSize = calculateAdvancedBatchSize(vacantSlots, expiredCount, movieId);
            
            if (batchSize <= 0) {
                return 0;
            }

            logger.info("[{}:{}] 고급 배치 입장 처리 시작 - 빈 슬롯: {}, 만료된 세션: {}, 배치 크기: {}", 
                type, movieId, vacantSlots, expiredCount, batchSize);

            // 대기열에서 배치 단위로 사용자들 추출
            Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, batchSize);

            if (!admittedUsers.isEmpty()) {
                // 모든 사용자를 활성 세션에 추가하고 알림 전송
                for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                    String requestId = entry.getKey();
                    String sessionId = entry.getValue();
                    
                    admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                    webSocketUpdateService.notifyAdmitted(requestId);
                }

                long processingTime = System.currentTimeMillis() - processingStartTime;
                
                // ★★★ 3단계: 상세 메트릭 기록 ★★★
                metricsService.recordEntry(movieId, admittedUsers.size(), processingTime);

                logger.info("[{}:{}] 고급 배치 입장 처리 완료 - {}명이 동시 입장했습니다. (처리시간: {}ms)", 
                    type, movieId, admittedUsers.size(), processingTime);
                
                return admittedUsers.size();
            }
        } catch (Exception e) {
            logger.error("[{}:{}] 고급 입장 처리 중 오류", type, movieId, e);
        }
        
        return 0;
    }

    /**
     * ★★★ 3단계: 성능 분석 기반 고급 배치 크기 계산 ★★★
     */
    private long calculateAdvancedBatchSize(long vacantSlots, int expiredCount, String movieId) {
        try {
            var calcInfo = sessionCalculator.getCalculationInfo();
            var performanceAnalysis = metricsService.getPerformanceAnalysis();
            
            int podCount = calcInfo.currentPodCount();
            
            // 기본 배치 크기 (Pod 수 기반)
            long baseBatchSize = Math.max(podCount, 1);
            
            // ★ 시스템 부하 상태에 따른 조정
            String systemHealth = (String) performanceAnalysis.get("systemHealth");
            double multiplier = switch (systemHealth) {
                case "OVERLOADED" -> 0.5;  // 부하가 높으면 보수적으로
                case "HEALTHY" -> 1.5;     // 여유 있으면 적극적으로
                default -> 1.0;
            };
            
            // ★ 대기열 증가 추세에 따른 조정
            boolean queueGrowing = (Boolean) performanceAnalysis.getOrDefault("isQueueGrowing", false);
            if (queueGrowing) {
                multiplier += 0.3; // 대기열이 증가하면 더 적극적으로
            }
            
            // ★ 영화별 대기자 수에 따른 조정
            long waitingCount = admissionService.getTotalWaitingCount("movie", movieId);
            if (waitingCount > 50) {
                multiplier += 0.2; // 대기자가 많으면 더 적극적으로
            }
            
            long adjustedBatchSize = Math.round(baseBatchSize * multiplier);
            
            // 안전한 범위 내에서 최종 결정
            long finalSize = Math.min(Math.min(vacantSlots, adjustedBatchSize), 15); // 최대 15명
            finalSize = Math.max(finalSize, 1); // 최소 1명
            
            logger.debug("고급 배치 크기 계산 - 기본: {}, 시스템상태: {}, 승수: {:.1f}, 최종: {}", 
                baseBatchSize, systemHealth, multiplier, finalSize);
                
            return finalSize;
            
        } catch (Exception e) {
            logger.error("고급 배치 크기 계산 중 오류, fallback 사용", e);
            return Math.min(vacantSlots, Math.max(expiredCount, 1));
        }
    }

    private String extractMovieId(String key) {
        Pattern pattern = Pattern.compile("active_sessions:movie:(.+)");
        Matcher matcher = pattern.matcher(key);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}