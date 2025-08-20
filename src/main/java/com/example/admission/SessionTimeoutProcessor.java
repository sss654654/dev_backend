package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);

    // AdmissionService와 키 접두사를 일치시킵니다.
    private static final String ACTIVE_USERS_KEY_PREFIX_MOVIE = "active_users:movie:";
    private static final String ACTIVE_USERS_KEY_COUPON = "active_users:coupon:global";

    private final StringRedisTemplate redisTemplate;

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    // 처리해야 할 영화 ID 목록을 스케줄러가 직접 관리합니다.
    private static final List<String> MOVIE_IDS = List.of("1", "2", "3", "4");

    public SessionTimeoutProcessor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 10초마다 실행하여 만료된 활성 세션을 정리합니다.
     */
    @Scheduled(fixedRate = 10000)
    public void cleanupExpiredSessions() {
        if (sessionTimeoutSeconds <= 0) {
            return; // 타임아웃이 0 이하로 설정되었으면 실행하지 않음
        }

        // 1. 만료 기준 시간을 계산합니다 (현재 시간 - 타임아웃 시간).
        long expirationTime = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);

        // 2. 모든 영화 큐에 대해 만료된 세션을 정리합니다.
        for (String movieId : MOVIE_IDS) {
            String activeUsersKey = ACTIVE_USERS_KEY_PREFIX_MOVIE + movieId;

            // 만료된 시간(score) 이전의 모든 멤버(requestId)를 Redis에서 삭제합니다.
            Long removedCount = redisTemplate.opsForZSet().removeRangeByScore(activeUsersKey, 0, expirationTime);

            if (removedCount != null && removedCount > 0) {
                logger.info("[MovieID: {}] 만료된 활성 세션 {}개를 정리했습니다.", movieId, removedCount);
            }
        }

        // 3. ★★★ 쿠폰 큐에 대해서도 동일하게 만료된 세션을 정리합니다. ★★★
        Long removedCouponCount = redisTemplate.opsForZSet().removeRangeByScore(ACTIVE_USERS_KEY_COUPON, 0, expirationTime);
        if (removedCouponCount != null && removedCouponCount > 0) {
            logger.info("[Coupon] 만료된 활성 세션 {}개를 정리했습니다.", removedCouponCount);
        }
    }
}
