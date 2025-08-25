// com/example/admission/service/AdmissionService.java
package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import jakarta.annotation.PostConstruct;
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

    private static final String ACTIVE_MOVIES  = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    @Value("${admission.session-timeout-seconds:30}")
    private long sessionTimeoutSeconds;

    private final RedisTemplate<String, String> redisTemplate;
    private final DynamicSessionCalculator sessionCalculator;

    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.sessionCalculator = sessionCalculator;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
    }

    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        // ... (이 메소드는 변경 없음) ...
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey   = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;
        String userTimeoutKey = activeUserTimeoutKey(type, id, member);
        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        Long currentActiveSessions = setOps.size(activeSessionsKey);
        if (currentActiveSessions == null) currentActiveSessions = 0L;
        if (currentActiveSessions < maxActiveSessions) {
            setOps.add(activeSessionsKey, member);
            redisTemplate.opsForValue().set(userTimeoutKey, "active", Duration.ofSeconds(sessionTimeoutSeconds));
            setOps.add(ACTIVE_MOVIES, id);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            zSetOps.add(waitingQueueKey, member, Instant.now().toEpochMilli());
            setOps.add(WAITING_MOVIES, id);
            Long myRank = zSetOps.rank(waitingQueueKey, member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, myRank != null ? myRank + 1 : null, totalWaiting);
        }
    }

    // [핵심 수정] admitUsersFromQueue 메소드를 Redis 클러스터 호환 방식으로 변경
    public Map<String, String> admitUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = waitingQueueKey(type, id);
        String activeSessionsKey = activeSessionsKey(type, id);

        // 1. 대기열에서 입장시킬 사용자 목록을 먼저 조회합니다. (단일 키 명령어)
        Set<String> membersToAdmit = zSetOps.range(waitingQueueKey, 0, count - 1);
        if (membersToAdmit == null || membersToAdmit.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> resultMap = new HashMap<>();
        
        // 2. 각 사용자에 대해 개별적으로 활성 세션 추가 및 타임아웃 설정을 합니다.
        for (String member : membersToAdmit) {
            // 활성 세션에 추가 (단일 키 명령어)
            setOps.add(activeSessionsKey, member);
            // 타임아웃 키 설정 (단일 키 명령어)
            redisTemplate.opsForValue().set(activeUserTimeoutKey(type, id, member), "active", Duration.ofSeconds(sessionTimeoutSeconds));

            int idx = member.indexOf(':');
            if (idx > 0) {
                resultMap.put(member.substring(0, idx), member.substring(idx + 1));
            }
        }

        // 3. 마지막으로, 입장시킨 사용자들을 대기열에서 제거합니다. (단일 키 명령어)
        zSetOps.remove(waitingQueueKey, membersToAdmit.toArray());
        
        logger.info("[{}] 대기열에서 {}명을 활성 세션으로 이동 완료", id, membersToAdmit.size());
        return resultMap;
    }
    
    // (이하 다른 메소드들은 변경 없음)
    // ...
    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey   = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;
        if (Boolean.TRUE.equals(setOps.isMember(activeSessionsKey, member))) {
            setOps.remove(activeSessionsKey, member);
            redisTemplate.delete(activeUserTimeoutKey(type, id, member));
        }
        if (zSetOps.score(waitingQueueKey, member) != null) {
            zSetOps.remove(waitingQueueKey, member);
        }
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(waitingQueueKey(type, id));
        return count != null ? count : 0;
    }

    public long getActiveSessionCount(String type, String id) {
        Long count = setOps.size(activeSessionsKey(type, id));
        return count != null ? count : 0;
    }

    public long getVacantSlots(String type, String id) {
        long max = sessionCalculator.calculateMaxActiveSessions();
        long current = getActiveSessionCount(type, id);
        return Math.max(0, max - current);
    }

    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = waitingQueueKey(type, id);
        List<String> members = zSetOps.rangeWithScores(waitingQueueKey, 0, -1)
            .stream()
            .map(ZSetOperations.TypedTuple::getValue)
            .filter(Objects::nonNull)
            .toList();
        Map<String, Long> userRanks = new HashMap<>();
        long rank = 1;
        for (String member : members) {
            int idx = member.indexOf(':');
            if (idx > 0) {
                userRanks.put(member.substring(0, idx), rank++);
            }
        }
        return userRanks;
    }

    public Set<String> getActiveQueueMovieIds() {
        Set<String> movieIds = setOps.members(WAITING_MOVIES);
        return movieIds != null ? movieIds : Collections.emptySet();
    }
    
    private String activeSessionsKey(String type, String id) { return "active_sessions:" + type + ":" + id; }
    private String waitingQueueKey(String type, String id) { return "waiting_queue:" + type + ":" + id; }
    private String activeUserTimeoutKey(String type, String id, String member) { return "active_user_ttl:" + type + ":" + id + ":" + member; }
}