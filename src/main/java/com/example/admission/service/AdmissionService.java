// src/main/java/com/example/admission/service/AdmissionService.java
package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);
    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;

    @Value("${SESSION_TIMEOUT_SECONDS:30}")
    private long sessionTimeoutSeconds;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, 
                           DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    private String activeSessionsKey(String type, String id) { 
        return "active_sessions:" + type + ":" + id; 
    }
    
    private String waitingQueueKey(String type, String id) { 
        return "waiting_queue:" + type + ":" + id; 
    }

    // ✅ 핵심 수정: Redis Lua 스크립트를 사용한 원자적 입장 처리
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        String activeKey = activeSessionsKey(type, id);
        String waitingKey = waitingQueueKey(type, id);
        long now = System.currentTimeMillis();
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();

        // Lua 스크립트로 원자적 처리
        String luaScript = """
            local activeKey = KEYS[1]
            local waitingKey = KEYS[2]
            local maxSessions = tonumber(ARGV[1])
            local member = ARGV[2]
            local now = tonumber(ARGV[3])
            
            -- 현재 활성 세션 수 확인
            local currentActive = redis.call('ZCARD', activeKey)
            
            if currentActive < maxSessions then
                -- 즉시 활성 세션으로 추가
                redis.call('ZADD', activeKey, now, member)
                return {1, 'SUCCESS'}
            else
                -- 대기열에 추가
                redis.call('ZADD', waitingKey, now, member)
                local rank = redis.call('ZRANK', waitingKey, member)
                local totalWaiting = redis.call('ZCARD', waitingKey)
                return {2, 'QUEUED', rank + 1, totalWaiting}
            end
        """;

        RedisScript<List> script = RedisScript.of(luaScript, List.class);
        List<Object> result = redisTemplate.execute(script, 
            Arrays.asList(activeKey, waitingKey), 
            String.valueOf(maxSessions), member, String.valueOf(now));

        // 영화를 활성 목록에 추가
        setOps.add(ACTIVE_MOVIES, id);
        if (Integer.parseInt(result.get(0).toString()) == 2) {
            setOps.add(WAITING_MOVIES, id);
        }

        // 결과 처리
        if (Integer.parseInt(result.get(0).toString()) == 1) {
            logger.info("✅ [{}] 즉시 입장 허가 - requestId: {}...", id, requestId.substring(0, 8));
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장", requestId, null, null);
        } else {
            Long myRank = Long.parseLong(result.get(2).toString());
            Long totalWaiting = Long.parseLong(result.get(3).toString());
            logger.info("⏳ [{}] 대기열 등록 - requestId: {}... 순위: {}/{}", 
                       id, requestId.substring(0, 8), myRank, totalWaiting);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열 등록", requestId, myRank, totalWaiting);
        }
    }
    
    // ✅ 수정: 배치 처리도 원자적으로 개선
    public List<String> admitNextUsers(String type, String id, long count) {
        String waitingKey = waitingQueueKey(type, id);
        String activeKey = activeSessionsKey(type, id);
        long now = System.currentTimeMillis();
        
        // Lua 스크립트로 원자적 배치 처리
        String luaScript = """
            local waitingKey = KEYS[1]
            local activeKey = KEYS[2]
            local count = tonumber(ARGV[1])
            local now = tonumber(ARGV[2])
            
            -- 대기열에서 다음 사용자들 가져오기
            local waitingUsers = redis.call('ZRANGE', waitingKey, 0, count - 1)
            local admitted = {}
            
            for i = 1, #waitingUsers do
                local user = waitingUsers[i]
                -- 대기열에서 제거
                redis.call('ZREM', waitingKey, user)
                -- 활성 세션에 추가
                redis.call('ZADD', activeKey, now, user)
                table.insert(admitted, user)
            end
            
            return admitted
        """;

        RedisScript<List> script = RedisScript.of(luaScript, List.class);
        List<String> admitted = redisTemplate.execute(script, 
            Arrays.asList(waitingKey, activeKey), 
            String.valueOf(count), String.valueOf(now));

        if (admitted != null && !admitted.isEmpty()) {
            logger.info("🚀 [{}] {}명을 대기열에서 활성 세션으로 승격", id, admitted.size());
        }

        return admitted != null ? admitted : Collections.emptyList();
    }

    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        zSetOps.remove(activeSessionsKey(type, id), member);
        zSetOps.remove(waitingQueueKey(type, id), member);
        logger.info("👋 [{}] 사용자 퇴장 - requestId: {}...", id, requestId.substring(0, 8));
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
        Set<String> activeMovies = setOps.members(ACTIVE_MOVIES);
        Set<String> waitingMovies = setOps.members(WAITING_MOVIES);
        Set<String> allMovies = new HashSet<>();
        if (activeMovies != null) allMovies.addAll(activeMovies);
        if (waitingMovies != null) allMovies.addAll(waitingMovies);
        return allMovies;
    }

    public Set<String> findExpiredActiveSessions(String type, String id) {
        long expirationThreshold = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
        return zSetOps.rangeByScore(activeSessionsKey(type, id), 0, expirationThreshold);
    }

    public void removeActiveSessions(String type, String id, Set<String> expiredMembers) {
        if (expiredMembers != null && !expiredMembers.isEmpty()) {
            zSetOps.remove(activeSessionsKey(type, id), expiredMembers.toArray(new String[0]));
            logger.info("🧹 [{}] {}개 만료 세션 정리", id, expiredMembers.size());
        }
    }
}