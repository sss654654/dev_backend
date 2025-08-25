package com.example.admission.service;

import com.example.admission.dto.AdmissionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AdmissionMetricsService {
    private static final Logger logger = LoggerFactory.getLogger(AdmissionMetricsService.class);

    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    private final RedisTemplate<String, String> redisTemplate;
    private final AdmissionService admissionService;
    private final com.example.admission.service.DynamicSessionCalculator sessionCalculator;

    private final Map<String, AtomicLong> realtimeMetrics = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> historicalData = new ConcurrentHashMap<>();
    private final int HISTORY_LIMIT = 100;

    public AdmissionMetricsService(RedisTemplate<String, String> redisTemplate,
                                   AdmissionService admissionService,
                                   com.example.admission.service.DynamicSessionCalculator sessionCalculator) {
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

            com.example.admission.service.DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
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
        com.example.admission.service.DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
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

    // 🔹 SCAN 제거: 인덱스 기반 합산
    public long getAllActiveSessionsCount() {
        Set<String> movieIds = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
        if (movieIds == null || movieIds.isEmpty()) return 0L;

        long total = 0L;
        for (String id : movieIds) {
            Long sz = redisTemplate.opsForSet().size(activeSessionsKey(id));
            if (sz != null) total += sz;
        }
        return total;
    }

    public long getAllWaitingUsersCount() {
        // waiting_movies가 비어있을 수 있으니 active_movies와 합집합으로 안전하게 집계
        Set<String> union = new HashSet<>();
        Set<String> waiting = redisTemplate.opsForSet().members(WAITING_MOVIES);
        Set<String> active  = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
        if (waiting != null) union.addAll(waiting);
        if (active  != null) union.addAll(active);
        if (union.isEmpty()) return 0L;

        long total = 0L;
        for (String id : union) {
            Long sz = redisTemplate.opsForZSet().zCard(waitingQueueKey(id));
            if (sz != null) total += sz;
        }
        return total;
    }

    private String activeSessionsKey(String movieId) {
        return "active_sessions:movie:" + movieId;
    }

    private String waitingQueueKey(String movieId) {
        return "waiting_queue:movie:" + movieId;
    }
}
