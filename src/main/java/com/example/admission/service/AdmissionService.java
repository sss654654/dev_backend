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

    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    @Value("${admission.session-timeout-seconds:30}")
    private long sessionTimeoutSeconds;

    private final RedisTemplate<String, String> redisTemplate;
    private final DynamicSessionCalculator sessionCalculator;

    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, 
                           DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.sessionCalculator = sessionCalculator;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
    }

    /**
     * 대기열 진입/즉시 입장 처리 - 활성세션은 Set으로 변경
     */
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        setOps.add(ACTIVE_MOVIES, id);
        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentActiveSessions = getTotalActiveCount(type, id);

        logger.info("[{}] 입장 요청 - 현재 활성세션: {}/{}, 요청자: {}:{}", 
                    id, currentActiveSessions, maxActiveSessions, requestId, sessionId);

        if (currentActiveSessions < maxActiveSessions) {
            // 활성 세션은 Set으로 관리 (SessionTimeoutProcessor와 일관성 유지)
            String activeKey = activeSessionsKey(type, id);
            String member = requestId + ":" + sessionId;

            setOps.add(activeKey, member);

            // TTL 키 설정으로 30초 후 자동 만료 감지
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));

            logger.info("[{}] 즉시 입장 허가 - 현재 활성세션: {}/{}", id, currentActiveSessions + 1, maxActiveSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            // 대기열은 ZSet으로 관리 (순서 보장)
            setOps.add(WAITING_MOVIES, id);
            String waitingKey = waitingQueueKey(type, id);
            String member = requestId + ":" + sessionId;

            zSetOps.add(waitingKey, member, Instant.now().toEpochMilli());

            Long myRank = zSetOps.rank(waitingKey, member);
            if (myRank == null) {
                 myRank = 0L;
            }
            Long totalWaiting = zSetOps.zCard(waitingKey);

            logger.info("[{}] 대기열 등록 - 순위 {}/{}, 요청자: {}:{}", 
                        id, myRank + 1, totalWaiting, requestId, sessionId);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, myRank + 1, totalWaiting);
        }
    }

    /**
     * 대기열/활성 세션에서 사용자 제거
     */
    public void leave(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        
        // 활성 세션에서 제거 (Set)
        setOps.remove(activeSessionsKey(type, id), member);
        
        // 대기열에서 제거 (ZSet)
        zSetOps.remove(waitingQueueKey(type, id), member);

        // TTL 키도 함께 삭제
        String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
        redisTemplate.delete(timeoutKey);
        
        logger.info("[{}] 사용자 퇴장 처리 완료: {}:{}", id, requestId, sessionId);
    }

    /**
     * 대기열에서 다음 사용자들을 가져와 활성세션으로 승격 (QueueProcessor에서 호출)
     */
    public List<String> admitNextUsers(String type, String id, long count) {
        String waitingKey = waitingQueueKey(type, id);
        String activeKey = activeSessionsKey(type, id);

        // 대기열에서 가장 오래 기다린 순서대로 가져오기 (ZSet)
        Set<String> membersToAdmit = zSetOps.range(waitingKey, 0, count - 1);
        if (membersToAdmit == null || membersToAdmit.isEmpty()) {
            return Collections.emptyList();
        }

        // 활성 세션으로 이동 (Set으로 변경)
        for (String member : membersToAdmit) {
            setOps.add(activeKey, member);

            // 새로운 활성 세션에도 TTL 설정
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));
        }

        // 대기열에서 승격된 사용자들 제거 (ZSet)
        zSetOps.removeRange(waitingKey, 0, count - 1);
        
        logger.info("[{}] 대기열에서 {}명을 활성 세션으로 이동 완료", id, membersToAdmit.size());
        return new ArrayList<>(membersToAdmit);
    }
    
    /**
     * 활성 세션 수 조회 (Set으로 변경)
     */
    public long getTotalActiveCount(String type, String id) {
        Long count = setOps.size(activeSessionsKey(type, id));
        return count != null ? count : 0;
    }

    /**
     * 대기열 수 조회 (ZSet)
     */
    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(waitingQueueKey(type, id));
        return count != null ? count : 0;
    }
    
    /**
     * 특정 사용자의 대기열 순위 조회
     */
    public Long getUserWaitingRank(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        return zSetOps.rank(waitingQueueKey(type, id), member);
    }

    /**
     * 빈 자리 개수 계산 (QueueProcessor에서 사용)
     */
    public long getVacantSlots(String type, String id) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        return Math.max(0, maxSessions - currentSessions);
    }

    /**
     * 활성 대기열이 있는 영화 ID 목록 조회 (QueueProcessor에서 사용)
     */
    public Set<String> getActiveQueueMovieIds() {
        Set<String> waitingMovies = setOps.members(WAITING_MOVIES);
        return waitingMovies != null ? waitingMovies : Collections.emptySet();
    }

    /**
     * 모든 대기자의 순위 정보 조회 (QueueProcessor에서 사용)
     */
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingKey, 0, -1);
        
        Map<String, Long> ranks = new HashMap<>();
        if (members != null) {
            long rank = 1;
            for (String member : members) {
                String[] parts = member.split(":", 2);
                if (parts.length >= 1) {
                    ranks.put(parts[0], rank); // requestId를 키로 사용
                }
                rank++;
            }
        }
        return ranks;
    }

    private String activeSessionsKey(String type, String id) {
        return "active_sessions:" + type + ":" + id;
    }

    private String waitingQueueKey(String type, String id) {
        return "waiting_queue:" + type + ":" + id;
    }
}