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

                // This movie's processing is assigned to this pod
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    continue; // Skip if not assigned to this pod
                }

                totalProcessedMovies++;

                Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
                if (members == null || members.isEmpty()) continue;

                List<String> expiredMembers = new ArrayList<>();

                // ✅ CROSSSLOT error solution: individual lookups instead of multiGet()
                for (String member : members) {
                    try {
                        String timeoutKey = "active_users:movie:" + movieId + ":" + member;
                        String value = redisTemplate.opsForValue().get(timeoutKey);
                        if (value == null) {
                            expiredMembers.add(member);
                        }
                    } catch (Exception e) {
                        logger.warn("Error checking timeout key for ({}): {}", member, e.getMessage());
                        // Assume member is expired if key check fails
                        expiredMembers.add(member);
                    }
                }

                if (!expiredMembers.isEmpty()) {
                    long expiredCount = redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());
                    totalTimeouts += expiredCount;

                    logger.warn("[{}] Cleaned up {} timed-out active sessions.", activeSessionsKey, expiredCount);

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
            logger.error("Error during expired session cleanup", e);
        }

        if (totalProcessedMovies > 0) {
            loadBalancer.updatePodLoad(totalProcessedMovies);
            long processingTime = System.currentTimeMillis() - startTime;
            logger.debug("Timeout processing complete - Movies processed: {}, Total timeouts: {}, Time taken: {}ms",
                    totalProcessedMovies, totalTimeouts, processingTime);
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            redisTemplate.execute((RedisConnection connection) -> {
                try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(50).build())) {
                    while (cursor.hasNext()) {
                        try {
                            String key = new String(cursor.next(), StandardCharsets.UTF_8);
                            keys.add(key);
                        } catch (Exception e) {
                            logger.warn("Error processing Redis SCAN key: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error during Redis SCAN cursor processing", e);
                }
                return null;
            });
        } catch (Exception e) {
            logger.error("Error executing Redis connection", e);
            logger.warn("SCAN failed, returning empty key list.");
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