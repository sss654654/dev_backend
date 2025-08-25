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

    /**
     * 🔹 대기열 진입/즉시 입장 처리
     */
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        setOps.add(ACTIVE_MOVIES, id);
        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentActiveSessions = getTotalActiveCount(type, id);

        if (currentActiveSessions < maxActiveSessions) {
            long now = Instant.now().toEpochMilli();
            String activeKey = activeSessionsKey(type, id);
            String member = requestId + ":" + sessionId;

            zSetOps.add(activeKey, member, now);

            // ✨ 수정된 부분 시작: 활성 세션의 TTL(만료 시간)을 설정합니다.
            // 이 키를 SessionTimeoutProcessor가 감지하여 만료된 세션을 자동으로 정리합니다.
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));
            // ✨ 수정된 부분 끝

            logger.info("✅ 즉시 입장 성공 및 TTL 설정 완료: requestId={}", requestId);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            setOps.add(WAITING_MOVIES, id);
            String waitingKey = waitingQueueKey(type, id);
            String member = requestId + ":" + sessionId;

            zSetOps.add(waitingKey, member, Instant.now().toEpochMilli());

            Long myRank = zSetOps.rank(waitingKey, member);
            if (myRank == null) {
                 myRank = 0L;
            }
            Long totalWaiting = zSetOps.zCard(waitingKey);

            logger.info("⏳ 대기열 등록: requestId={}, 순위: {}/{}", requestId, myRank + 1, totalWaiting);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, myRank + 1, totalWaiting);
        }
    }

    /**
     * 🔹 대기열/활성 세션에서 사용자 제거
     */
    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        zSetOps.remove(activeSessionsKey(type, id), member);
        zSetOps.remove(waitingQueueKey(type, id), member);

        // TTL 키도 함께 삭제하여 불필요한 처리를 방지
        String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
        redisTemplate.delete(timeoutKey);
    }

    /**
     * 🔹 대기열에서 다음 사용자들을 가져와 입장 처리
     */
    public List<String> admitNextUsers(String type, String id, long count) {
        String waitingKey = waitingQueueKey(type, id);
        String activeKey = activeSessionsKey(type, id);

        Set<String> membersToAdmit = zSetOps.range(waitingKey, 0, count - 1);
        if (membersToAdmit == null || membersToAdmit.isEmpty()) {
            return Collections.emptyList();
        }

        long now = Instant.now().toEpochMilli();
        for (String member : membersToAdmit) {
            zSetOps.add(activeKey, member, now);

            // ✨ 수정된 부분 시작: 대기열에서 입장하는 사용자에게도 TTL을 설정합니다.
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));
            // ✨ 수정된 부분 끝
        }

        zSetOps.removeRange(waitingKey, 0, count - 1);
        return new ArrayList<>(membersToAdmit);
    }
    
    public long getTotalActiveCount(String type, String id) {
        Long count = zSetOps.zCard(activeSessionsKey(type, id));
        return count != null ? count : 0;
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(waitingQueueKey(type, id));
        return count != null ? count : 0;
    }

    public long getVacantSlots(String type, String id) {
        long max = sessionCalculator.calculateMaxActiveSessions();
        long current = getTotalActiveCount(type, id);
        return Math.max(0, max - current);
    }

    /**
     * 🔹 특정 사용자의 현재 대기 순위 조회
     */
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingKey, 0, -1);
        
        if (members == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Long> ranks = new LinkedHashMap<>();
        long rank = 1;
        for (String member : members) {
            // member는 "requestId:sessionId" 형태
            String requestId = member.split(":")[0];
            ranks.put(requestId, rank++);
        }
        return ranks;
    }

    public Set<String> getActiveQueueMovieIds() {
        try {
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
}