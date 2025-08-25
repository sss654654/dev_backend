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
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey   = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;
        String userTimeoutKey = activeUserTimeoutKey(type, id, member);

        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        Long currentActiveSessions = setOps.size(activeSessionsKey);
        if (currentActiveSessions == null) currentActiveSessions = 0L;

        logger.info("[{}] 입장 요청 - 현재 활성세션: {}/{}, 요청자: {}", id, currentActiveSessions, maxActiveSessions, member);

        if (currentActiveSessions < maxActiveSessions) {
            setOps.add(activeSessionsKey, member);
            redisTemplate.opsForValue().set(userTimeoutKey, "active", Duration.ofSeconds(sessionTimeoutSeconds));
            setOps.add(ACTIVE_MOVIES, id);

            logger.info("[{}] 즉시 입장 허가 - 현재 활성세션: {}/{}", id, currentActiveSessions + 1, maxActiveSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            double score = Instant.now().toEpochMilli();
            zSetOps.add(waitingQueueKey, member, score);
            setOps.add(WAITING_MOVIES, id);

            Long myRank = zSetOps.rank(waitingQueueKey, member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey);

            logger.info("[{}] 대기열 등록 - 순위 {}/{}, 요청자: {}", id, myRank != null ? myRank + 1 : "?", totalWaiting, member);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.",
                    requestId, myRank != null ? myRank + 1 : null, totalWaiting);
        }
    }

    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey   = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;

        if (Boolean.TRUE.equals(setOps.isMember(activeSessionsKey, member))) {
            setOps.remove(activeSessionsKey, member);
            redisTemplate.delete(activeUserTimeoutKey(type, id, member));
            logger.info("[{}] 활성 세션에서 퇴장: {}", id, member);
        }
        if (zSetOps.score(waitingQueueKey, member) != null) {
            zSetOps.remove(waitingQueueKey, member);
            logger.info("[{}] 대기열에서 퇴장: {}", id, member);
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

    public Map<String, String> admitUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = waitingQueueKey(type, id);
        String activeSessionsKey = activeSessionsKey(type, id);

        Set<String> membersToAdmit = zSetOps.range(waitingQueueKey, 0, count - 1);
        if (membersToAdmit == null || membersToAdmit.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> resultMap = new HashMap<>();
        for (String member : membersToAdmit) {
            setOps.add(activeSessionsKey, member);
            redisTemplate.opsForValue().set(activeUserTimeoutKey(type, id, member), "active", Duration.ofSeconds(sessionTimeoutSeconds));

            int idx = member.indexOf(':');
            if (idx > 0) {
                resultMap.put(member.substring(0, idx), member.substring(idx + 1));
            }
        }

        zSetOps.remove(waitingQueueKey, membersToAdmit.toArray());
        logger.info("[{}] 대기열에서 {}명을 활성 세션으로 이동 완료", id, membersToAdmit.size());
        return resultMap;
    }

    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = waitingQueueKey(type, id);
        
        try {
            Set<ZSetOperations.TypedTuple<String>> rangeWithScores = zSetOps.rangeWithScores(waitingQueueKey, 0, -1);
            if (rangeWithScores == null || rangeWithScores.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, Long> userRanks = new HashMap<>();
            long rank = 1;
            
            for (ZSetOperations.TypedTuple<String> tuple : rangeWithScores) {
                String member = tuple.getValue();
                if (member != null) {
                    int idx = member.indexOf(':');
                    if (idx > 0) {
                        userRanks.put(member.substring(0, idx), rank++);
                    }
                }
            }
            return userRanks;
        } catch (Exception e) {
            logger.error("[{}] 사용자 순위 조회 중 오류 발생", id, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 🔹 SCAN 제거: 직접적인 Set 접근으로 영화 ID 조회
     * NumberFormatException 에러 해결을 위해 SCAN 명령을 사용하지 않습니다.
     */
    public Set<String> getActiveQueueMovieIds() {
        try {
            // waiting_movies Set에서 직접 멤버들을 가져옵니다 (SCAN 사용하지 않음)
            Set<String> movieIds = setOps.members(WAITING_MOVIES);
            
            if (movieIds == null) {
                return Collections.emptySet();
            }
            
            logger.debug("대기열이 있는 영화 {}개 조회됨", movieIds.size());
            return movieIds;
            
        } catch (Exception e) {
            logger.error("대기열 영화 ID 조회 중 오류 발생", e);
            return Collections.emptySet();
        }
    }

    /**
     * 🔹 전체 활성 영화 ID 조회 (SCAN 사용하지 않음)
     */
    public Set<String> getAllActiveMovieIds() {
        try {
            Set<String> movieIds = setOps.members(ACTIVE_MOVIES);
            return movieIds != null ? movieIds : Collections.emptySet();
        } catch (Exception e) {
            logger.error("활성 영화 ID 조회 중 오류 발생", e);
            return Collections.emptySet();
        }
    }

    private String activeSessionsKey(String type, String id) { 
        return "active_sessions:" + type + ":" + id; 
    }
    
    private String waitingQueueKey(String type, String id) { 
        return "waiting_queue:" + type + ":" + id; 
    }
    
    private String activeUserTimeoutKey(String type, String id, String member) { 
        return "active_user_ttl:" + type + ":" + id + ":" + member; 
    }
}