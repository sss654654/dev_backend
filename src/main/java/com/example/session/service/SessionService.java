package com.example.session.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Service
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    public static final String COOKIE_NAME = "SID";
    private static final String KEY_PREFIX = "session:"; // Redis 키 접두사
    private static final Duration SESSION_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public SessionService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 새로운 세션을 생성하고 Redis에 저장합니다.
     * @param sessionId 생성할 세션의 ID
     */
    public void createSession(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, "1", SESSION_TTL);
        logger.info("새로운 세션 생성: {}", key);
    }

    /**
     * HTTP 요청의 쿠키에서 세션 ID를 읽어옵니다.
     * @param request HttpServletRequest
     * @return 세션 ID Optional
     */
    public Optional<String> readSessionIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    /**
     * 주어진 세션 ID가 Redis에 유효하게 존재하는지 확인합니다.
     * @param sessionId 검증할 세션 ID
     * @return 유효하면 true
     */
    public boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }

    /**
     * 요청에 유효한 세션 쿠키가 있는지 확인하고, 없으면 예외를 발생시킵니다.
     * 성공 시 세션 ID를 반환하고, 세션의 만료 시간을 갱신(Sliding TTL)합니다.
     * @param request HttpServletRequest
     * @return 유효한 세션 ID
     */
    public String requireValidSessionOrThrow(HttpServletRequest request) {
        String sessionId = readSessionIdFromCookie(request)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "세션 쿠키(SID)가 존재하지 않습니다."));

        if (!isSessionValid(sessionId)) {
            throw new ResponseStatusException(HttpStatus.valueOf(419), "세션이 만료되었거나 유효하지 않습니다.");
        }

        // 슬라이딩 TTL: 세션 만료 시간 갱신
        redisTemplate.expire(KEY_PREFIX + sessionId, SESSION_TTL);

        return sessionId;
    }
}