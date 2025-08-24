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
    private final com.example.admission.service.DynamicSessionCalculator sessionCalculator;
    
    // 실시간 메트릭 저장
    private final Map<String, AtomicLong> realtimeMetrics = new ConcurrentHashMap<>();
    private final Map<String, Queue<Long>> historicalData = new ConcurrentHashMap<>();
    
    public AdmissionMetricsService(RedisTemplate<String, String> redisTemplate,
                                   AdmissionService admissionService,
                                   com.example.admission.service.DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;
        // 메트릭 초기화
        initializeMetrics();
    }
    
    private void initializeMetrics() {
        realtimeMetrics.put("total_entries_processed", new AtomicLong(0));
        realtimeMetrics.put("total_timeouts", new AtomicLong(0));
        realtimeMetrics.put("total_queue_joins", new AtomicLong(0));
        realtimeMetrics.put("batch_processes", new AtomicLong(0));
        realtimeMetrics.put("avg_processing_time", new AtomicLong(0));
        
        // 히스토리컬 데이터 (최근 100개 데이터 포인트)
        historicalData.put("throughput_per_minute", new LinkedList<>());
        historicalData.put("queue_sizes", new LinkedList<>());
        historicalData.put("pod_utilization", new LinkedList<>());
    }

    /**
     * 입장 처리 메트릭 기록
     */
    public void recordEntry(String movieId, int batchSize, long processingTimeMs) {
        realtimeMetrics.get("total_entries_processed").addAndGet(batchSize);
        realtimeMetrics.get("batch_processes").incrementAndGet();
        
        // 평균 처리 시간 업데이트 (간단 이동 평균)
        long currentAvg = realtimeMetrics.get("avg_processing_time").get();
        long newAvg = (currentAvg + processingTimeMs) / 2;
        realtimeMetrics.get("avg_processing_time").set(newAvg);
        
        logger.debug("메트릭 기록 - 영화: {}, 배치 크기: {}, 처리 시간: {}ms",
                movieId, batchSize, processingTimeMs);
    }

    /**
     * 타임아웃 메트릭 기록
     */
    public void recordTimeout(String movieId, int timeoutCount) {
        realtimeMetrics.get("total_timeouts").addAndGet(timeoutCount);
        logger.debug("타임아웃 메트릭 기록 - 영화: {}, 타임아웃 수: {}", movieId, timeoutCount);
    }

    /**
     * 대기열 진입 메트릭 기록
     */
    public void recordQueueJoin(String movieId) {
        realtimeMetrics.get("total_queue_joins").incrementAndGet();
    }

    /**
     * 5초마다 시스템 메트릭 수집
     */
    @Scheduled(fixedRate = 5000)
    public void collectSystemMetrics() {
        try {
            var calcInfo = sessionCalculator.getCalculationInfo();
            
            // Pod 활용도 계산
            long totalSessions = getAllActiveSessionsCount();
            double podUtilization = calcInfo.calculatedMaxSessions() > 0
                    ? (double) totalSessions / calcInfo.calculatedMaxSessions() * 100
                    : 0;
            
            // 전체 대기자 수 계산
            long totalWaiting = getAllWaitingCount();
            
            // 히스토리컬 데이터 업데이트
            updateHistoricalData("pod_utilization", Math.round(podUtilization));
            updateHistoricalData("queue_sizes", totalWaiting);
            
            logger.debug("시스템 메트릭 수집 - Pod 활용도: {}%, 총 대기자: {}, 총 활성 세션: {}",
                    String.format("%.1f", podUtilization), totalWaiting, totalSessions);
        } catch (Exception e) {
            logger.error("시스템 메트릭 수집 중 오류", e);
        }
    }

    /**
     * 1분마다 처리량 메트릭 계산
     */
    @Scheduled(fixedRate = 60000)
    public void calculateThroughputMetrics() {
        try {
            long currentProcessed = realtimeMetrics.get("total_entries_processed").get();
            
            // Redis에서 이전 값 가져오기
            String previousKey = "metrics:previous_processed";
            String previousStr = redisTemplate.opsForValue().get(previousKey);
            long previousProcessed = previousStr != null ? Long.parseLong(previousStr) : currentProcessed;
            
            // 분당 처리량 계산
            long throughputPerMinute = currentProcessed - previousProcessed;
            updateHistoricalData("throughput_per_minute", throughputPerMinute);
            
            // 현재 값을 Redis에 저장
            redisTemplate.opsForValue().set(previousKey, String.valueOf(currentProcessed));
            
            logger.info("처리량 메트릭 - 분당 처리: {}명", throughputPerMinute);
        } catch (Exception e) {
            logger.error("처리량 메트릭 계산 중 오류", e);
        }
    }
    
    private void updateHistoricalData(String key, long value) {
        Queue<Long> queue = historicalData.get(key);
        if (queue == null) {
            queue = new LinkedList<>();
            historicalData.put(key, queue);
        }
        if (queue.size() >= 100) {
            queue.poll(); // 오래된 데이터 제거
        }
        queue.offer(value);
    }

    /**
     * ✅ SCAN을 사용한 ZSET 카드 합계 계산 - Cursor<byte[]> 사용
     */
    private long sumZCardByPattern(String pattern) {
        long total = 0L;
        var factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            logger.warn("RedisConnectionFactory를 가져올 수 없어 SCAN을 건너뜁니다. pattern={}", pattern);
            return 0L;
        }

        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(100)
                .build();

        try (RedisConnection conn = factory.getConnection();
             Cursor<byte[]> cursor = conn.scan(options)) { // ★ 중요: Cursor<byte[]>
            
            while (cursor.hasNext()) {
                // ★ byte[]를 String으로 안전하게 변환
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                Long cardinalityCount = redisTemplate.opsForZSet().zCard(key);
                if (cardinalityCount != null) {
                    total += cardinalityCount;
                }
            }
        } catch (Exception e) {
            logger.error("패턴 {} 스캔 중 오류", pattern, e);
        }
        return total;
    }

    private long getAllActiveSessionsCount() {
        // active_sessions:movie:* 의 ZSET cardinality 합계
        return sumZCardByPattern("active_sessions:movie:*");
    }

    private long getAllWaitingCount() {
        // waiting_queue:movie:* 의 ZSET cardinality 합계
        return sumZCardByPattern("waiting_queue:movie:*");
    }

    /**
     * 현재 메트릭 정보 반환
     */
    public AdmissionMetrics getCurrentMetrics() {
        var calcInfo = sessionCalculator.getCalculationInfo();
        return new AdmissionMetrics(
                System.currentTimeMillis(),
                calcInfo.currentPodCount(),
                calcInfo.calculatedMaxSessions(),
                getAllActiveSessionsCount(),
                getAllWaitingCount(),
                realtimeMetrics.get("total_entries_processed").get(),
                realtimeMetrics.get("total_timeouts").get(),
                realtimeMetrics.get("total_queue_joins").get(),
                realtimeMetrics.get("batch_processes").get(),
                realtimeMetrics.get("avg_processing_time").get(),
                new ArrayList<>(historicalData.getOrDefault("throughput_per_minute", new LinkedList<>())),
                new ArrayList<>(historicalData.getOrDefault("queue_sizes", new LinkedList<>())),
                new ArrayList<>(historicalData.getOrDefault("pod_utilization", new LinkedList<>()))
        );
    }

    /**
     * 성능 분석 및 추천사항 생성
     */
    public Map<String, Object> getPerformanceAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        try {
            var metrics = getCurrentMetrics();
            
            // 처리량 분석
            double avgThroughput = metrics.throughputHistory().stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0);
            
            // Pod 활용도 분석
            double avgUtilization = metrics.podUtilizationHistory().stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0);
            
            // 대기열 트렌드 분석
            List<Long> queueSizes = metrics.queueSizeHistory();
            boolean queueGrowing = queueSizes.size() >= 2 &&
                    queueSizes.get(queueSizes.size() - 1) > queueSizes.get(queueSizes.size() - 2);
            
            analysis.put("avgThroughputPerMinute", Math.round(avgThroughput));
            analysis.put("avgPodUtilization", Math.round(avgUtilization * 10) / 10.0);
            analysis.put("isQueueGrowing", queueGrowing);
            analysis.put("recommendScaleUp", avgUtilization > 80 && queueGrowing);
            analysis.put("recommendScaleDown", avgUtilization < 30 && !queueGrowing);
            analysis.put("systemHealth", avgUtilization < 90 ? "HEALTHY" : "OVERLOADED");
            
            logger.debug("성능 분석 완료 - 평균 처리량: {}, 평균 활용도: {}%",
                    Math.round(avgThroughput), Math.round(avgUtilization));
        } catch (Exception e) {
            logger.error("성능 분석 중 오류", e);
            analysis.put("error", e.getMessage());
        }
        return analysis;
    }
}