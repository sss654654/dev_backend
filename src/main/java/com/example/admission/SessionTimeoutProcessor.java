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
import java.util.HashSet; // 👈 [ERROR FIXED] This line was missing
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
                
                totalProcessedMovies++;
                
                Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
                if (members == null || members.isEmpty()) continue;

                List<String> expiredMembers = new ArrayList<>();
                List<String> keysToCheck = new ArrayList<>();
                for (String member : members) {
                    keysToCheck.add("active_users:movie:" + movieId + ":" + member);
                }

                List<String> existingTimeoutKeys = redisTemplate.opsForValue().multiGet(keysToCheck);

                for (int i = 0; i < members.size(); i++) {
                    if (existingTimeoutKeys.get(i) == null) {
                        expiredMembers.add(new ArrayList<>(members).get(i));
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

    private String extractMovieId(String key) {
        Pattern pattern = Pattern.compile("active_sessions:movie:(.+)");
        Matcher matcher = pattern.matcher(key);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}