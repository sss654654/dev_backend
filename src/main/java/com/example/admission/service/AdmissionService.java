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

import java.time.Instant;
import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);
    private static final String WAITING_MOVIES = "waiting_movies";
    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;

    @Value("${SESSION_TIMEOUT_SECONDS:90}")
    private long sessionTimeoutSeconds;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    private String activeSessionsKey(String type, String id) { return "active_sessions:" + type + ":" + id; }
    private String waitingQueueKey(String type, String id) { return "waiting_queue:" + type + ":" + id; }

    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        String member = requestId + ":" + sessionId;

        if (currentSessions < maxSessions) {
            zSetOps.add(activeSessionsKey(type, id), member, System.currentTimeMillis());
            setOps.add(WAITING_MOVIES, id);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장", requestId, null, null);
        } else {
            setOps.add(WAITING_MOVIES, id);
            zSetOps.add(waitingQueueKey(type, id), member, Instant.now().toEpochMilli());
            Long myRank = zSetOps.rank(waitingQueueKey(type, id), member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey(type, id));
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열 등록", requestId, myRank != null ? myRank + 1 : 0L, totalWaiting);
        }
    }

    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        zSetOps.remove(activeSessionsKey(type, id), member);
        zSetOps.remove(waitingQueueKey(type, id), member);
    }

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

    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        Set<ZSetOperations.TypedTuple<String>> rankedUsers = zSetOps.rangeWithScores(waitingKey, 0, -1);
        Map<String, Long> userRanks = new LinkedHashMap<>();
        if (rankedUsers != null) {
            long rank = 1;
            for (ZSetOperations.TypedTuple<String> tuple : rankedUsers) {
                String member = tuple.getValue();
                if (member != null) userRanks.put(member.split(":")[0], rank++);
            }
        }
        return userRanks;
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