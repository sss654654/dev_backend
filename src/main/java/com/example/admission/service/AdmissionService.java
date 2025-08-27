// src/main/java/com/example/admission/service/AdmissionService.java
package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);
    private static final String WAITING_MOVIES = "waiting_movies_final";

    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;

    @Value("${SESSION_TIMEOUT_SECONDS:10}")
    private long sessionTimeoutSeconds;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    private String activeSessionsKey(String type, String id) { return "active_sessions_zset_final:" + type + ":" + id; }
    private String waitingQueueKey(String type, String id) { return "waiting_queue_final:" + type + ":" + id; }

    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        String activeKey = activeSessionsKey(type, id);
        String waitingKey = waitingQueueKey(type, id);
        String lockKey = "lock:admission_final:" + type + ":" + id;

        // ✅ [핵심 수정] 분산 락을 획득해야만 입장/대기열 등록 로직 전체를 수행
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, "locked", Duration.ofSeconds(5)))) {
            try {
                long maxSessions = sessionCalculator.calculateMaxActiveSessions();
                long currentSessions = getTotalActiveCount(type, id);
                
                // 1. 활성 세션에 자리가 있는 경우
                if (currentSessions < maxSessions) {
                    zSetOps.add(activeKey, member, System.currentTimeMillis());
                    setOps.add(WAITING_MOVIES, id);
                    logger.info("✅ [{}] 즉시 입장 (현재: {}/{})", id, currentSessions + 1, maxSessions);
                    return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장", requestId, null, null);
                } 
                // 2. 활성 세션이 꽉 찬 경우 (락 안에서 바로 대기열 등록)
                else {
                    setOps.add(WAITING_MOVIES, id);
                    zSetOps.add(waitingKey, member, Instant.now().toEpochMilli());
                    Long myRank = zSetOps.rank(waitingKey, member);
                    Long totalWaiting = zSetOps.zCard(waitingKey);
                    
                    logger.info("⏳ [{}] 대기열 등록 (순위: {}/{})", id, myRank != null ? myRank + 1 : "N/A", totalWaiting);
                    return new EnterResponse(EnterResponse.Status.QUEUED, "대기열 등록", requestId, myRank != null ? myRank + 1 : 0L, totalWaiting);
                }
            } finally {
                redisTemplate.delete(lockKey); // 모든 처리가 끝나면 락 해제
            }
        } else {
            // 락 획득 실패 시, 잠시 후 재시도를 유도하기 위해 대기열로 보냄 (안전장치)
            setOps.add(WAITING_MOVIES, id);
            zSetOps.add(waitingKey, member, Instant.now().toEpochMilli() + 1000); // 락 경합 밀림을 고려해 1초 추가
            Long myRank = zSetOps.rank(waitingKey, member);
            Long totalWaiting = zSetOps.zCard(waitingKey);
            logger.warn("⚠️ [{}] 락 경합 발생, 안전하게 대기열로 등록 (순위: {}/{})", id, myRank != null ? myRank + 1 : "N/A", totalWaiting);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열 등록", requestId, myRank != null ? myRank + 1 : 0L, totalWaiting);
        }
    }
    
    // ... 이하 다른 메서드들은 이전과 동일하며, 안정적으로 동작합니다 ...
    public List<String> admitNextUsers(String type, String id, long count) {
        String waitingKey = waitingQueueKey(type, id);
        String activeKey = activeSessionsKey(type, id);
        
        Set<String> waitingUsers = zSetOps.range(waitingKey, 0, count - 1);

        if (waitingUsers == null || waitingUsers.isEmpty()) return Collections.emptyList();

        List<String> admittedUsers = new ArrayList<>();
        for (String member : waitingUsers) {
            if (zSetOps.remove(waitingKey, member) > 0) {
                zSetOps.add(activeKey, member, System.currentTimeMillis());
                admittedUsers.add(member);
            }
        }
        return admittedUsers;
    }

    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        zSetOps.remove(activeSessionsKey(type, id), member);
        zSetOps.remove(waitingQueueKey(type, id), member);
    }
    
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingKey, 0, -1);
        Map<String, Long> ranks = new LinkedHashMap<>();
        if (members != null) {
            long rank = 1;
            for (String member : members) {
                ranks.put(member.split(":")[0], rank++);
            }
        }
        return ranks;
    }

    public boolean isUserInActiveSession(String type, String id, String sessionId, String requestId) {
        return zSetOps.score(activeSessionsKey(type, id), requestId + ":" + sessionId) != null;
    }

    public Long getUserRank(String type, String id, String sessionId, String requestId) {
        Long rank = zSetOps.rank(waitingQueueKey(type, id), requestId + ":" + sessionId);
        return (rank != null) ? rank + 1 : null;
    }

    public long getTotalActiveCount(String type, String id) {
        return Optional.ofNullable(zSetOps.zCard(activeSessionsKey(type, id))).orElse(0L);
    }

    public long getTotalWaitingCount(String type, String id) {
        return Optional.ofNullable(zSetOps.zCard(waitingQueueKey(type, id))).orElse(0L);
    }

    public long getVacantSlots(String type, String id) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        return Math.max(0, maxSessions - currentSessions);
    }
    
    public Set<String> getActiveQueueMovieIds() {
        return Optional.ofNullable(setOps.members(WAITING_MOVIES)).orElse(Collections.emptySet());
    }

    public Set<String> findExpiredActiveSessions(String type, String id) {
        long expirationThreshold = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
        return zSetOps.rangeByScore(activeSessionsKey(type, id), 0, expirationThreshold);
    }

    public void removeActiveSessions(String type, String id, Set<String> expiredMembers) {
        if (expiredMembers != null && !expiredMembers.isEmpty()) {
            zSetOps.remove(activeSessionsKey(type, id), expiredMembers.toArray(new String[0]));
        }
    }
}