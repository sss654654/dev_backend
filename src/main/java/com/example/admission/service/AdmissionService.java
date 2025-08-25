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

    // [수정] 메소드 이름을 'tryEnter'에서 'enter'로 변경
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
        // [수정] 변수 타입을 Set에서 List로 변경하여 타입 불일치 해결
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