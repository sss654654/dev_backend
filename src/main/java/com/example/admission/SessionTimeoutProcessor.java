package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);

    private static final String ACTIVE_MOVIES = "active_movies";

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
            // 🔹 SCAN 제거: 활성 영화 인덱스를 통해 순회
            Set<String> movieIds = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            if (movieIds == null || movieIds.isEmpty()) return;

            for (String movieId : movieIds) {
                totalProcessedMovies++;

                String activeSessionsKey = activeSessionsKey(movieId);

                Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
                if (members == null || members.isEmpty()) {
                    // 남은 세션 없으면 인덱스 정리
                    redisTemplate.opsForSet().remove(ACTIVE_MOVIES, movieId);
                    continue;
                }

                List<String> expiredMembers = new ArrayList<>();

                // TTL 키 존재 여부로 타임아웃 판단 (기존 방식 유지)
                for (String member : members) {
                    try {
                        String timeoutKey = activeUserKey(movieId, member);
                        String value = redisTemplate.opsForValue().get(timeoutKey);
                        if (value == null) {
                            expiredMembers.add(member);
                        }
                    } catch (Exception e) {
                        logger.warn("타임아웃 키 조회 중 오류 ({}): {}", member, e.getMessage());
                        // 조회 실패한 키는 만료된 것으로 간주
                        expiredMembers.add(member);
                    }
                }

                if (!expiredMembers.isEmpty()) {
                    Long removed = redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());
                    long removedCount = removed == null ? 0L : removed;
                    totalTimeouts += removedCount;

                    logger.warn("[{}] 타임아웃된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, removedCount);

                    for (String member : expiredMembers) {
                        int idx = member.indexOf(':');
                        if (idx > 0) {
                            String requestId = member.substring(0, idx);
                            webSocketUpdateService.notifyTimeout(requestId);
                        }
                    }

                    metricsService.recordTimeout(movieId, removedCount);

                    // 세션 0이면 인덱스에서 제거
                    Long remain = redisTemplate.opsForSet().size(activeSessionsKey);
                    if (remain == null || remain == 0) {
                        redisTemplate.opsForSet().remove(ACTIVE_MOVIES, movieId);
                    }
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

    private String activeSessionsKey(String movieId) {
        // 필요 시 해시태그 적용 가능: "active_sessions:movie:{" + movieId + "}"
        return "active_sessions:movie:" + movieId;
    }

    private String activeUserKey(String movieId, String member) {
        // 개별 TTL 키
        return "active_users:movie:" + movieId + ":" + member;
    }
}
