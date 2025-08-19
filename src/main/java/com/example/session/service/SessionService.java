
package com.example.session.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import static org.springframework.http.HttpStatus.*;


@Service
public class SessionService {


    public static final String COOKIE_NAME = "SID";
    private static final String KEY_PREFIX = "sess:";
    private static final Duration SESSION_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public SessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 세션 생성: Redis에 키 저장(+TTL) */
    public void createSession(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        redis.opsForValue().set(key, "1", SESSION_TTL);
    }

    /** (선택) 세션 무효화 */
    public void invalidateSession(String sessionId) {
        redis.delete(KEY_PREFIX + sessionId);
    }

    /** 쿠키에서 SID 추출 */
    public Optional<String> readSessionIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    /** Redis에 존재하는 유효 세션인지 확인 (없으면 false) */
    public boolean isSessionValid(String sessionId) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + sessionId));
    }

    /** 존재/유효 둘 다 검사. 실패 시 401/419 던짐. 성공 시 SID 반환 + TTL 슬라이딩 갱신 */
    public String requireValidSessionOrThrow(HttpServletRequest request, HttpServletResponse response) {
        String sid = readSessionIdFromCookie(request)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "NO_SESSION_COOKIE"));

        String key = KEY_PREFIX + sid;
        Boolean exists = redis.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(419), "SESSION_EXPIRED"); // 419: custom timeout
        }

        // 슬라이딩 TTL: Redis TTL 연장
        redis.expire(key, SESSION_TTL);

        // (선택) 쿠키도 Max-Age 갱신
        boolean https = request.isSecure();
        String sameSite = https ? "None" : "Lax";
        ResponseCookie refreshed = ResponseCookie.from(COOKIE_NAME, sid)
                .httpOnly(true)
                .secure(https)
                .sameSite(sameSite)
                .path("/")
                .maxAge(SESSION_TTL)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshed.toString());

        return sid;
    }
}

