package com.example.admission.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);
    
    private static final String WAITING_QUEUE_KEY_PREFIX = "waiting_queue:movie:";
    private static final String ACTIVE_SESSIONS_KEY_PREFIX = "active_sessions:movie:";
    private static final String ACTIVE_MOVIE_QUEUES_KEY = "active_movie_queues";

    @Value("${admission.max-active-sessions}")
    private long maxActiveSessions;

    private final RedisTemplate<String, String> redisTemplate;
    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    // 변경점: SimpMessagingTemplate 의존성 완전 제거
    public AdmissionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
    }

    public AdmissionResult tryEnter(String sessionId, String requestId, String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        
        logger.info("[MovieID: {}] [RequestID: {}] 입장 시도: sessionId={}", movieId, requestId, sessionId);

        if (setOps.isMember(activeSessionsKey, sessionId)) {
            logger.warn("[MovieID: {}] [RequestID: {}] 이미 활성 세션에 있는 사용자입니다: sessionId={}", movieId, requestId, sessionId);
            return AdmissionResult.SUCCESS;
        }
        if (zSetOps.rank(waitingQueueKey, sessionId) != null) {
            zSetOps.remove(waitingQueueKey, sessionId);
            logger.warn("[MovieID: {}] [RequestID: {}] 이미 대기열에 있는 사용자를 발견하여, 기존 위치에서 제거 후 맨 뒤로 보냅니다: sessionId={}", movieId, requestId, sessionId);
        }

        Long currentActiveCount = setOps.size(activeSessionsKey);
        if (currentActiveCount == null) currentActiveCount = 0L;

        if (currentActiveCount < this.maxActiveSessions) {
            setOps.add(activeSessionsKey, sessionId);
            logger.info("[MovieID: {}] [RequestID: {}] 즉시 입장 성공: sessionId={}", movieId, requestId, sessionId);
            return AdmissionResult.SUCCESS;
        } else {
            zSetOps.add(waitingQueueKey, sessionId, System.currentTimeMillis());
            setOps.add(ACTIVE_MOVIE_QUEUES_KEY, movieId);
            logger.info("[MovieID: {}] [RequestID: {}] 대기열 등록: sessionId={}", movieId, requestId, sessionId);
            return AdmissionResult.QUEUED;
        }
    }
    
    public void leave(String sessionId, String requestId, String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        Long removedCount = setOps.remove(activeSessionsKey, sessionId);

        // 변경점: 브로드캐스트 관련 로직 완전 제거
        if (removedCount != null && removedCount > 0) {
            logger.info("[MovieID: {}] [RequestID: {}] 세션 이탈: sessionId={}. 빈자리 발생.", movieId, requestId, sessionId);
        }
    }
    
    // 변경점: 빈자리를 계산하는 로직을 Service 내부에 캡슐화하여 제공
    public long getVacantSlots(String movieId) {
        long activeCount = getActiveSessionCount(movieId);
        long vacantCount = maxActiveSessions - activeCount;
        return Math.max(0, vacantCount); // 0보다 작은 값이 나오지 않도록 보장
    }

    public Long getUserRank(String sessionId, String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        return zSetOps.rank(waitingQueueKey, sessionId);
    }
    
    public boolean isActiveUser(String sessionId, String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        return setOps.isMember(activeSessionsKey, sessionId);
    }

    public void removeFromWaitingQueue(String sessionId, String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        zSetOps.remove(waitingQueueKey, sessionId);
    }
    
    public Set<String> getWaitingUsers(String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        return zSetOps.range(waitingQueueKey, 0, -1);
    }

    public Set<String> popNextUsersFromQueue(String movieId, long count) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        Set<String> nextUsers = zSetOps.range(waitingQueueKey, 0, count - 1);
        if (nextUsers != null && !nextUsers.isEmpty()) {
            zSetOps.removeRange(waitingQueueKey, 0, count - 1);
        }
        return nextUsers;
    }
    
    public void addToActiveSessions(String movieId, String sessionId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        setOps.add(activeSessionsKey, sessionId);
    }

    public long getActiveSessionCount(String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        Long count = setOps.size(activeSessionsKey);
        return count != null ? count : 0;
    }

    public Long getTotalWaitingCount(String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        Long count = zSetOps.size(waitingQueueKey);
        return count != null ? count : 0L;
    }
    
    public Set<String> getActiveMovieQueues() {
        return setOps.members(ACTIVE_MOVIE_QUEUES_KEY);
    }

    public void removeMovieQueueIfEmpty(String movieId) {
        if (getTotalWaitingCount(movieId) == 0) {
            setOps.remove(ACTIVE_MOVIE_QUEUES_KEY, movieId);
        }
    }

    public enum AdmissionResult {
        SUCCESS, QUEUED
    }
}