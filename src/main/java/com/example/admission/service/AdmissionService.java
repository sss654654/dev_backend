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
    private SetOperations<String, String> setOps; // ACTIVE_MOVIE_QUEUES_KEY는 Set으로 유지

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

        if (this.isActiveUser(sessionId, movieId)) {
            logger.warn("[MovieID: {}] ... 이미 활성 세션에 있는 사용자입니다...", movieId, requestId, sessionId);
            return AdmissionResult.SUCCESS;
        }
        if (zSetOps.rank(waitingQueueKey, sessionId) != null) {
            zSetOps.remove(waitingQueueKey, sessionId);
            logger.warn("[MovieID: {}] ... 이미 대기열에 있는 사용자 발견, 맨 뒤로 보냅니다...", movieId, requestId, sessionId);
        }

        if (this.getVacantSlots(movieId) > 0) {
            this.addToActiveSessions(movieId, sessionId); // 변경: 이제 시간도 함께 기록
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
        // 변경점: Sorted Set에서 제거
        Long removedCount = zSetOps.remove(activeSessionsKey, sessionId);

        if (removedCount != null && removedCount > 0) {
            logger.info("[MovieID: {}] [RequestID: {}] 세션 이탈: sessionId={}. 빈자리 발생.", movieId, requestId, sessionId);
        }
    }
    
    public long getVacantSlots(String movieId) {
        long activeCount = getActiveSessionCount(movieId);
        return Math.max(0, maxActiveSessions - activeCount);
    }
    
    // --- (이하 메서드들도 Sorted Set 기준으로 변경) ---

    public Long getUserRank(String sessionId, String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        return zSetOps.rank(waitingQueueKey, sessionId);
    }
    
    public boolean isActiveUser(String sessionId, String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        // score가 있다는 것은 멤버가 존재한다는 의미
        return zSetOps.score(activeSessionsKey, sessionId) != null;
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
        // Sorted Set에 현재 시간을 score로 하여 추가
        zSetOps.add(activeSessionsKey, sessionId, System.currentTimeMillis());
    }

    public long getActiveSessionCount(String movieId) {
        String activeSessionsKey = ACTIVE_SESSIONS_KEY_PREFIX + movieId;
        Long count = zSetOps.zCard(activeSessionsKey); // zCard로 개수 조회
        return count != null ? count : 0;
    }

    public Long getTotalWaitingCount(String movieId) {
        String waitingQueueKey = WAITING_QUEUE_KEY_PREFIX + movieId;
        Long count = zSetOps.zCard(waitingQueueKey);
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