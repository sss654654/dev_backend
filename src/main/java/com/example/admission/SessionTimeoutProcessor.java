package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    
    private final RedisTemplate<String, String> redisTemplate;
    private final WebSocketUpdateService webSocketUpdateService;
    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    @Value("${admission.session-timeout-seconds:30}")
    private long sessionTimeoutSeconds;

    // ★ 수정: 생성자에서 LoadBalancingOptimizer를 주입받도록 변경
    public SessionTimeoutProcessor(RedisTemplate<String, String> redisTemplate,
                                     WebSocketUpdateService webSocketUpdateService,
                                     AdmissionMetricsService metricsService,
                                     LoadBalancingOptimizer loadBalancer) {
        this.redisTemplate = redisTemplate;
        this.webSocketUpdateService = webSocketUpdateService;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    @Scheduled(fixedDelayString = "${admission.session-processor-interval-ms:2000}")
    public void processExpiredSessions() {
        long startTime = System.currentTimeMillis();
        int totalProcessedMovies = 0;
        long totalTimeouts = 0;

        try {
            Set<String> activeSessionKeys = scanKeys("active_sessions:movie:*");

            for (String activeSessionsKey : activeSessionKeys) {
                String movieId = extractMovieId(activeSessionsKey);
                if (movieId == null) continue;
                
                // ★★★ 핵심 수정: 이 영화 처리가 내 담당인지 확인 ★★★
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    continue; // 내 담당이 아니면 건너뛰기
                }

                totalProcessedMovies++;
                
                Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
                if (members == null || members.isEmpty()) continue;

                List<String> expiredMembers = new ArrayList<>();
                
                for (String member : members) {
                    try {
                        String timeoutKey = "active_users:movie:" + movieId + ":" + member;
                        String value = redisTemplate.opsForValue().get(timeoutKey);
                        if (value == null) {
                            expiredMembers.add(member);
                        }
                    } catch (Exception e) {
                        logger.warn("타임아웃 키 조회 중 오류 ({}): {}", member, e.getMessage());
                        expiredMembers.add(member);
                    }
                }
                
                if (!expiredMembers.isEmpty()) {
                    long expiredCount = redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());
                    totalTimeouts += expiredCount;
                    
                    logger.warn("[{}] 타임아웃된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, expiredCount);
                    
                    expiredMembers.forEach(member -> {
                        if (member.contains(":")) {
                            String requestId = member.split(":", 2)[0];
                            webSocketUpdateService.notifyTimeout(requestId);
                        }
                    });
                    
                    metricsService.recordTimeout(movieId, expiredCount);
                }
            }
        } catch (Exception e) {
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
        
        if (totalProcessedMovies > 0) {
            loadBalancer.updatePodLoad(totalProcessedMovies);
            long processingTime = System.currentTimeMillis() - startTime;
            logger.debug("타임아웃 처리 완료 - 처리 영화: {}, 총 타임아웃: {}, 소요시간: {}ms",
                    totalProcessedMovies, totalTimeouts, processingTime);
        }
    }
    
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            redisTemplate.execute((RedisConnection connection) -> {
                try {
                    ScanOptions options = ScanOptions.scanOptions()
                            .match(pattern)
                            .count(50)
                            .build();
                    
                    try (Cursor<byte[]> cursor = connection.scan(options)) {
                        while (cursor.hasNext()) {
                            try {
                                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                                keys.add(key);
                            } catch (Exception e) {
                                logger.warn("Redis SCAN 키 처리 중 오류: {}", e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Redis SCAN cursor 처리 중 오류", e);
                    }
                } catch (Exception e) {
                    logger.error("Redis SCAN 옵션 설정 중 오류", e);
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("Redis connection 실행 중 오류", e);
            logger.warn("SCAN 실패로 빈 키 목록 반환");
        }
        
        return keys;
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