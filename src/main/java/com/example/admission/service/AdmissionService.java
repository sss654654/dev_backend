package com.example.admission.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class AdmissionService {

    private static final String WAITING_QUEUE_KEY = "waiting_queue";
    private static final String ACTIVE_SESSIONS_KEY = "active_sessions";

    @Value("${admission.max-active-sessions}")
    private long maxActiveSessions;

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    private final RedisTemplate<String, String> redisTemplate;
    private ZSetOperations<String, String> zSetOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
    }

    public enum UserStatus { ADMITTED, WAITING }
    public enum AdmissionResult { SUCCESS, QUEUED }

    public AdmissionResult tryEnter(String sessionId) {
        cleanupExpiredSessions();
        Long currentActiveCount = zSetOps.size(ACTIVE_SESSIONS_KEY);
        if (currentActiveCount == null) currentActiveCount = 0L;

        if (currentActiveCount < maxActiveSessions) {
            addToActiveSessions(sessionId);
            return AdmissionResult.SUCCESS;
        } else {
            zSetOps.add(WAITING_QUEUE_KEY, sessionId, System.currentTimeMillis());
            return AdmissionResult.QUEUED;
        }
    }

    public void leave(String sessionId) {
        zSetOps.remove(ACTIVE_SESSIONS_KEY, sessionId);
    }

    public void addToActiveSessions(String sessionId) {
        long expirationTime = System.currentTimeMillis() + (sessionTimeoutSeconds * 1000);
        zSetOps.add(ACTIVE_SESSIONS_KEY, sessionId, expirationTime);
    }

    public void cleanupExpiredSessions() {
        long currentTime = System.currentTimeMillis();
        zSetOps.removeRangeByScore(ACTIVE_SESSIONS_KEY, 0, currentTime);
    }

    public Set<String> popNextUsersFromQueue(long count) {
        Set<String> nextUsers = zSetOps.range(WAITING_QUEUE_KEY, 0, count - 1);
        if (nextUsers != null && !nextUsers.isEmpty()) {
            zSetOps.removeRange(WAITING_QUEUE_KEY, 0, count - 1);
        }

        return nextUsers;
    }

    public long getActiveSessionCount() {
        Long count = zSetOps.size(ACTIVE_SESSIONS_KEY);
        return count != null ? count : 0;
    }

    // ✅ 컨트롤러에서 사용할 수 있도록 전체 대기자 수 조회 메서드 추가
    public long getTotalWaitingCount() {
        Long count = zSetOps.size(WAITING_QUEUE_KEY);
        return count != null ? count : 0;
    }

    public Map<String, Object> getUserStatusAndRank(String sessionId) {
        if (zSetOps.score(ACTIVE_SESSIONS_KEY, sessionId) != null) {
            return Map.of("status", UserStatus.ADMITTED);
        }

        Long rank = zSetOps.rank(WAITING_QUEUE_KEY, sessionId);
        if (rank != null) {
            return Map.of("status", UserStatus.WAITING, "rank", rank + 1);
        }
        return null;
    }


}