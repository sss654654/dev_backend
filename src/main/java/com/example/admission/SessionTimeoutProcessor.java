package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    private static final String ACTIVE_SESSIONS_KEY_PREFIX = "active_sessions:movie:";

    private final AdmissionService admissionService;
    private final StringRedisTemplate redisTemplate;

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    public SessionTimeoutProcessor(AdmissionService admissionService, StringRedisTemplate redisTemplate) {
        this.admissionService = admissionService;
        this.redisTemplate = redisTemplate;
    }

    // 10초마다 실행하여 만료된 세션을 확인
    @Scheduled(fixedRate = 10000)
    public void cleanupExpiredSessions() {
        if (sessionTimeoutSeconds <= 0) {
            return; // 타임아웃이 설정되지 않았으면 실행하지 않음
        }

        // 1. 활성 큐가 있는 모든 영화 ID를 가져옴
        Set<String> movieIds = admissionService.getActiveMovieQueues();
        
        // 대기열은 없지만 활성 세션만 있는 경우도 있으므로, Redis에서 직접 스캔하는 것이 더 정확합니다.
        // 이 예제에서는 간결함을 위해 AdmissionService를 활용합니다.
        // 실제 운영에서는 SCAN 명령어를 사용하는 것이 좋습니다.

        if (movieIds.isEmpty()) {
            return;
        }

        logger.trace("만료된 활성 세션 정리를 시작합니다. 대상 영화 수: {}", movieIds.size());

        // 2. 만료 기준 시간을 계산 (현재 시간 - 타임아웃 시간)
        long expirationTime = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);

        for (String movieId : movieIds) {
            String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
            
            // 3. 만료된 시간(score) 이전의 모든 멤버(세션)를 Redis에서 삭제
            Long removedCount = redisTemplate.opsForZSet().removeRangeByScore(activeSessionsKey, 0, expirationTime);

            if (removedCount != null && removedCount > 0) {
                logger.info("[MovieID: {}] 만료된 활성 세션 {}개를 정리했습니다.", movieId, removedCount);
            }
        }
    }
}