package com.example.admission.service;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AdmissionService {

    // --- 상태 저장을 위한 Redis 키 정의 ---
    private static final String WAITING_QUEUE_KEY = "waiting_queue";
    private static final String ACTIVE_SESSIONS_KEY = "active_sessions";
    
    // --- 시스템 정책 ---
    private static final long MAX_ACTIVE_SESSIONS = 50; // 최대 50명만 동시 접속 허용

    private final RedisTemplate<String, String> redisTemplate;
    private ZSetOperations<String, String> zSetOps; // 대기열 (Sorted Set)
    private SetOperations<String, String> setOps;   // 활성 세션 (Set)

    public AdmissionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
    }

    /**
     * 사용자의 입장을 시도합니다.
     * @return AdmissionResult (SUCCESS: 즉시 입장, QUEUED: 대기열 등록)
     */
    public AdmissionResult tryEnter(String sessionId) {
        // 1. 현재 활성 세션 수를 확인합니다. (SCARD active_sessions)
        Long currentActiveCount = setOps.size(ACTIVE_SESSIONS_KEY);
        if (currentActiveCount == null) currentActiveCount = 0L;

        // 2. 자리가 있는지 확인합니다.
        if (currentActiveCount < MAX_ACTIVE_SESSIONS) {
            // 2a. 자리가 있으면, 활성 세션에 추가합니다. (SADD active_sessions sessionId)
            setOps.add(ACTIVE_SESSIONS_KEY, sessionId);
            return AdmissionResult.SUCCESS; // 즉시 입장 성공
        } else {
            // 2b. 자리가 없으면, 대기열에 추가합니다. (ZADD waiting_queue timestamp sessionId)
            zSetOps.add(WAITING_QUEUE_KEY, sessionId, System.currentTimeMillis());
            return AdmissionResult.QUEUED; // 대기열 등록
        }
    }

    /**
     * 사용자가 예매를 완료하거나 이탈했을 때, 활성 세션에서 제거합니다.
     */
    public void leave(String sessionId) {
        setOps.remove(ACTIVE_SESSIONS_KEY, sessionId);
    }
    
    // --- 대기열 관련 메서드는 기존과 유사 ---
    public Long getUserRank(String sessionId) {
        return zSetOps.rank(WAITING_QUEUE_KEY, sessionId);
    }

    public Set<String> popNextUsersFromQueue(long count) {
        Set<String> nextUsers = zSetOps.range(WAITING_QUEUE_KEY, 0, count - 1);
        if (nextUsers != null && !nextUsers.isEmpty()) {
            zSetOps.removeRange(WAITING_QUEUE_KEY, 0, count - 1);
        }
        return nextUsers;
    }

    public long getActiveSessionCount() {
        Long count = setOps.size(ACTIVE_SESSIONS_KEY);
        return count != null ? count : 0;
    }

    // 결과 상태를 나타내기 위한 Enum
    public enum AdmissionResult {
        SUCCESS, QUEUED
    }
}