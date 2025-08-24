package com.example.admission.service;

import com.example.admission.dto.AdmissionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AdmissionMetricsService {
    private static final Logger logger = LoggerFactory.getLogger(AdmissionMetricsService.class);
    
    private final RedisTemplate<String, String> redisTemplate;
    private final AdmissionService admissionService;
    private final DynamicSessionCalculator sessionCalculator;
    
    private final Map<String, AtomicLong> realtimeMetrics = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> historicalData = new ConcurrentHashMap<>();
    private final int HISTORY_LIMIT = 100;

    public AdmissionMetricsService(RedisTemplate<String, String> redisTemplate,
                                   AdmissionService admissionService,
                                   DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;
        
        realtimeMetrics.put("totalEntriesProcessed", new AtomicLong(0));
        realtimeMetrics.put("totalTimeouts", new AtomicLong(0));
        realtimeMetrics.put("totalQueueJoins", new AtomicLong(0));
        realtimeMetrics.put("totalBatchProcesses", new AtomicLong(0));
        realtimeMetrics.put("totalProcessingTimeMs", new AtomicLong(0));
        realtimeMetrics.put("throughputLastMinute", new AtomicLong(0));
    }

    @Scheduled(fixedDelay = 10000)
    public void collectSystemMetrics() {
        try {
            long allActiveSessions = getAllActiveSessionsCount();
            long allWaitingUsers = getAllWaitingUsersCount();
            
            updateHistory("queueSizeHistory", allWaitingUsers);

            DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
            if (config.calculatedMaxSessions() > 0) {
                long utilization = (allActiveSessions * 100) / config.calculatedMaxSessions();
                updateHistory("podUtilizationHistory", utilization);
            }
            
            logger.debug("시스템 메트릭 수집: 활성 세션 = {}, 대기자 = {}", allActiveSessions, allWaitingUsers);
        } catch (Exception e) {
            logger.error("시스템 메트릭 수집 중 오류 발생", e);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void recordThroughput() {
        long throughput = realtimeMetrics.get("throughputLastMinute").getAndSet(0);
        updateHistory("throughputHistory", throughput);
        logger.info("분당 처리량 메트릭: {}명", throughput);
    }
    
    public AdmissionMetrics getCurrentMetrics() {
        DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
        long totalProcessed = realtimeMetrics.get("totalEntriesProcessed").get();
        long totalTime = realtimeMetrics.get("totalProcessingTimeMs").get();
        long avgProcessingTime = totalProcessed > 0 ? totalTime / totalProcessed : 0;
        
        return new AdmissionMetrics(
            System.currentTimeMillis(),
            config.currentPodCount(),
            config.calculatedMaxSessions(),
            getAllActiveSessionsCount(),
            getAllWaitingUsersCount(),
            totalProcessed,
            realtimeMetrics.get("totalTimeouts").get(),
            realtimeMetrics.get("totalQueueJoins").get(),
            realtimeMetrics.get("totalBatchProcesses").get(),
            avgProcessingTime,
            new ArrayList<>(historicalData.getOrDefault("throughputHistory", new LinkedList<>())),
            new ArrayList<>(historicalData.getOrDefault("queueSizeHistory", new LinkedList<>())),
            new ArrayList<>(historicalData.getOrDefault("podUtilizationHistory", new LinkedList<>()))
        );
    }

    public void recordTimeout(String movieId, long count) {
        realtimeMetrics.get("totalTimeouts").addAndGet(count);
    }

    public void resetMetrics() {
        realtimeMetrics.values().forEach(v -> v.set(0));
        historicalData.clear();
        logger.info("모든 메트릭이 초기화되었습니다.");
    }
    
    private void updateHistory(String key, long value) {
        historicalData.computeIfAbsent(key, k -> new LinkedList<>()).addLast(value);
        Deque<Long> queue = historicalData.get(key);
        while (queue.size() > HISTORY_LIMIT) {
            queue.removeFirst();
        }
    }
    
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        redisTemplate.execute((RedisConnection connection) -> {
            try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }

    private long sumSetCardByPattern(String pattern) {
        long totalCount = 0;
        try {
            Set<String> keys = scanKeys(pattern);
            if (keys.isEmpty()) {
                return 0;
            }
            for (String key : keys) {
                Long size = redisTemplate.opsForSet().size(key);
                if (size != null) {
                    totalCount += size;
                }
            }
        } catch (Exception e) {
            logger.error("패턴 {} 스캔 중 오류", pattern, e);
        }
        return totalCount;
    }
    
    private long sumZCardByPattern(String pattern) {
        long totalCount = 0;
        try {
            Set<String> keys = scanKeys(pattern);
            if (keys.isEmpty()) {
                return 0;
            }
            for (String key : keys) {
                Long size = redisTemplate.opsForZSet().zCard(key);
                if (size != null) {
                    totalCount += size;
                }
            }
        } catch (Exception e) {
            logger.error("패턴 {} 스캔 중 오류", pattern, e);
        }
        return totalCount;
    }

    public long getAllActiveSessionsCount() {
        return sumSetCardByPattern("active_sessions:movie:*");
    }

    public long getAllWaitingUsersCount() {
        return sumZCardByPattern("waiting_queue:movie:*");
    }
    
    public Map<String, Object> getPerformanceAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        AdmissionMetrics metrics = getCurrentMetrics();
        try {
            double avgThroughput = metrics.getAverageThroughputPerMinute();
            double avgUtilization = metrics.podUtilizationHistory().stream()
                    .mapToLong(Long::longValue).average().orElse(0.0);
            
            List<Long> queueSizes = metrics.queueSizeHistory();
            boolean queueGrowing = queueSizes.size() >= 2 &&
                    queueSizes.get(queueSizes.size() - 1) > queueSizes.get(queueSizes.size() - 2);
            
            analysis.put("avgThroughputPerMinute", Math.round(avgThroughput));
            analysis.put("avgPodUtilization", Math.round(avgUtilization * 10) / 10.0);
            analysis.put("isQueueGrowing", queueGrowing);
            analysis.put("recommendScaleUp", avgUtilization > 80 && queueGrowing);
            analysis.put("recommendScaleDown", avgUtilization < 30 && !queueGrowing);
            analysis.put("systemHealth", avgUtilization < 90 ? "HEALTHY" : "OVERLOADED");
            
        } catch (Exception e) {
            logger.error("성능 분석 중 오류", e);
            analysis.put("error", e.getMessage());
        }
        return analysis;
    }
}