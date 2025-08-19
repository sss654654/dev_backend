package com.example.session.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class SessionService {

    private static final String KEY_SESSION_PREFIX = "session:";
    private static final long SESSION_TTL_HOURS = 1; // 세션 유효 시간: 1시간

    private final RedisTemplate<String, String> redisTemplate;

    public SessionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 새로운 세션 ID를 생성하고 Redis에 1시간짜리 TTL과 함께 저장합니다.
     * @return 생성된 세션 ID
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        String sessionKey = KEY_SESSION_PREFIX + sessionId;
        // 세션 키를 생성하고 1시간짜리 TTL을 설정합니다. 값은 간단히 "active"로 저장.
        redisTemplate.opsForValue().set(sessionKey, "active", SESSION_TTL_HOURS, TimeUnit.HOURS);
        return sessionId;
    }
}