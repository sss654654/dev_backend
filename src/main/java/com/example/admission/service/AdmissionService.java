// src/main/java/com/example/admission/service/AdmissionService.java
package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import io.lettuce.core.RedisCommandExecutionException;

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

    // Hash Tag를 사용해 Redis 키들이 같은 슬롯에 배치되도록 함 (CROSSSLOT 오류 해결)
    private String activeSessionsKey(String type, String id) { 
        return "sessions:{" + id + "}:active"; 
    }

    private String waitingQueueKey(String type, String id) { 
        return "sessions:{" + id + "}:waiting"; 
    }

    // Redis WRONGTYPE 오류 방어 로직
    private void ensureKeyType(String key, String expectedType) {
        try {
            String actualType = redisTemplate.type(key).name();
            if (!"NONE".equals(actualType) && !expectedType.equals(actualType)) {
                logger.warn("키 타입 불일치 감지 (예상: {}, 실제: {}). 키를 삭제하고 재생성합니다.", 
                          expectedType, actualType);
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            logger.error("키 타입 확인 중 오류, 키 삭제 후 재생성", e);
            redisTemplate.delete(key);
        }
    }

    // 방어적 Redis 조회 메서드
    public long getTotalActiveCount(String type, String id) {
        String key = activeSessionsKey(type, id);
        try {
            ensureKeyType(key, "ZSET");
            return Optional.ofNullable(zSetOps.zCard(key)).orElse(0L);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("WRONGTYPE 오류 감지. 키 삭제 후 재시도");
                redisTemplate.delete(key);
                return 0L;
            }
            logger.error("Redis 조회 실패", e);
            return 0L;
        }
    }

    public long getTotalWaitingCount(String type, String id) {
        String key = waitingQueueKey(type, id);
        try {
            ensureKeyType(key, "ZSET");
            return Optional.ofNullable(zSetOps.zCard(key)).orElse(0L);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("WRONGTYPE 오류 감지. 키 삭제 후 재시도");
                redisTemplate.delete(key);
                return 0L;
            }
            logger.error("Redis 조회 실패", e);
            return 0L;
        }
    }

    public long getVacantSlots(String type, String id) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        return Math.max(0, maxSessions - currentSessions);
    }

    // CROSSSLOT 해결된 Redis Lua 스크립트 입장 처리
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        String activeKey = activeSessionsKey(type, id);
        String waitingKey = waitingQueueKey(type, id);
        
        // 키 타입 사전 검증 (WRONGTYPE 오류 방지)
        ensureKeyType(activeKey, "ZSET");
        ensureKeyType(waitingKey, "ZSET");
        
        long now = System.currentTimeMillis();
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();

        // Hash Tag 키들은 같은 슬롯에 위치하므로 원자적 처리 가능
        String luaScript = """
            local activeKey = KEYS[1]
            local waitingKey = KEYS[2]
            local maxSessions = tonumber(ARGV[1])
            local member = ARGV[2]
            local now = tonumber(ARGV[3])
            
            -- 현재 활성 세션 수 확인
            local activeCount = redis.call('ZCARD', activeKey)
            
            if activeCount < maxSessions then
                -- 즉시 활성 세션으로 추가
                redis.call('ZADD', activeKey, now, member)
                return {1, 'SUCCESS', activeCount + 1}
            else
                -- 대기열에 추가
                redis.call('ZADD', waitingKey, now, member)
                local rank = redis.call('ZRANK', waitingKey, member)
                local totalWaiting = redis.call('ZCARD', waitingKey)
                return {2, 'QUEUED', rank + 1, totalWaiting}
            end
        """;

        try {
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
                logger.info("즉시 입장 허가 - requestId: {}..., 현재 활성: {}/{}", 
                        requestId.substring(0, 8), result.get(2), maxSessions);
                return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장", requestId, null, null);
            } else {
                Long myRank = Long.parseLong(result.get(2).toString());
                Long totalWaiting = Long.parseLong(result.get(3).toString());
                logger.info("대기열 등록 완료 - rank: {}/{}, requestId: {}...", 
                        myRank, totalWaiting, requestId.substring(0, 8));
                return new EnterResponse(EnterResponse.Status.QUEUED, "대기열 등록", requestId, myRank, totalWaiting);
            }
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.error("Redis 스크립트 실행 오류. 키 정리 후 재시도 필요: {}", e.getMessage());
                // 문제 키들 정리
                redisTemplate.delete(activeKey);
                redisTemplate.delete(waitingKey);
                throw new RuntimeException("Redis 오류로 인한 입장 처리 실패. 잠시 후 다시 시도해주세요.", e);
            }
            throw e;
        }
    }

    // 오류 판별 유틸리티 (WRONGTYPE & CROSSSLOT 포함)
    private boolean isWrongTypeError(Exception e) {
        if (e instanceof RedisSystemException) {
            Throwable cause = e.getCause();
            if (cause instanceof RedisCommandExecutionException) {
                String message = ((RedisCommandExecutionException) cause).getMessage();
                return message.startsWith("WRONGTYPE") || message.contains("CROSSSLOT");
            }
        }
        return false;
    }

    // CROSSSLOT 해결된 대기자 승격 로직
    public List<String> admitNextUsers(String type, String id, long count) {
        String activeKey = activeSessionsKey(type, id);
        String waitingKey = waitingQueueKey(type, id);
        
        try {
            // 키 타입 사전 검증
            ensureKeyType(activeKey, "ZSET");
            ensureKeyType(waitingKey, "ZSET");
            
            // CROSSSLOT 해결: Hash Tag 키로 원자적 배치 처리
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

            long now = System.currentTimeMillis();
            RedisScript<List> script = RedisScript.of(luaScript, List.class);
            List<String> admitted = redisTemplate.execute(script, 
                Arrays.asList(waitingKey, activeKey), 
                String.valueOf(count), String.valueOf(now));

            if (admitted != null && !admitted.isEmpty()) {
                logger.info("{}명을 대기열에서 활성 세션으로 승격", admitted.size());
                return admitted;
            }

            return Collections.emptyList();

        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.error("사용자 승격 중 Redis 오류. 키 정리: {}", e.getMessage());
                redisTemplate.delete(activeKey);
                redisTemplate.delete(waitingKey);
            }
            logger.error("사용자 승격 실패", e);
            return Collections.emptyList();
        }
    }

    // 대기열 퇴장
    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        try {
            zSetOps.remove(activeSessionsKey(type, id), member);
            zSetOps.remove(waitingQueueKey(type, id), member);
            logger.info("사용자 퇴장 - requestId: {}...", requestId.substring(0, 8));
        } catch (Exception e) {
            logger.warn("퇴장 처리 중 오류 (무시)", e);
        }
    }

    // 활성 세션 확인
    public boolean isUserInActiveSession(String type, String id, String sessionId, String requestId) {
        try {
            String member = requestId + ":" + sessionId;
            String key = activeSessionsKey(type, id);
            ensureKeyType(key, "ZSET");
            return zSetOps.score(key, member) != null;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("활성 세션 확인 중 Redis 오류. 키 삭제");
                redisTemplate.delete(activeSessionsKey(type, id));
            }
            return false;
        }
    }

    // 사용자 순위 조회
    public Long getUserRank(String type, String id, String sessionId, String requestId) {
        try {
            String member = requestId + ":" + sessionId;
            String waitingKey = waitingQueueKey(type, id);
            ensureKeyType(waitingKey, "ZSET");
            Long rank = zSetOps.rank(waitingKey, member);
            return (rank != null) ? rank + 1 : null;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("순위 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(waitingQueueKey(type, id));
            }
            return null;
        }
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
        String key = activeSessionsKey(type, id);
        try {
            ensureKeyType(key, "ZSET");
            long expirationThreshold = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
            return zSetOps.rangeByScore(key, 0, expirationThreshold);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("만료 세션 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(key);
            }
            return Collections.emptySet();
        }
    }

    public void removeActiveSessions(String type, String id, Set<String> expiredMembers) {
        if (expiredMembers != null && !expiredMembers.isEmpty()) {
            String key = activeSessionsKey(type, id);
            try {
                zSetOps.remove(key, expiredMembers.toArray(new String[0]));
                logger.info("{}개 만료 세션 정리", expiredMembers.size());
            } catch (RedisSystemException e) {
                if (isWrongTypeError(e)) {
                    logger.warn("세션 정리 중 Redis 오류. 키 삭제");
                    redisTemplate.delete(key);
                }
            }
        }
    }

    // 현재 사용자 순위 조회 (대기열 상태 확인용)
    public Long getMyRank(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        String waitingKey = waitingQueueKey(type, id);
        
        try {
            ensureKeyType(waitingKey, "ZSET");
            Long rank = zSetOps.rank(waitingKey, member);
            return rank != null ? rank + 1 : null;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("순위 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(waitingKey);
            }
            return null;
        }
    }

    // 모든 사용자 순위 조회 (관리용)
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        try {
            ensureKeyType(waitingKey, "ZSET");
            Set<String> members = zSetOps.range(waitingKey, 0, -1);
            Map<String, Long> ranks = new LinkedHashMap<>();
            if (members != null) {
                long rank = 1;
                for (String member : members) {
                    try {
                        String requestId = member.split(":")[0];
                        ranks.put(requestId, rank++);
                    } catch (Exception e) {
                        logger.warn("멤버 파싱 실패: {}", member);
                    }
                }
            }
            return ranks;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("전체 순위 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(waitingKey);
            }
            return Collections.emptyMap();
        }
    }
}