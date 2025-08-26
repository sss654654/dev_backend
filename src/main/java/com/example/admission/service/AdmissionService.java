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
    private static final String WAITING_MOVIES = "waiting_movies";

    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;

    @Value("${admission.session.timeout:30}") // 기본 타임아웃 30초로 조정
    private long sessionTimeoutSeconds;

    public AdmissionService(RedisTemplate<String, String> redisTemplate,
                          DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    /**
     * 대기열 진입 로직
     */
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        
        logger.info("[{}] 입장 요청 - 현재 활성세션: {}/{}, 요청자: {}:{}", 
                   id, currentSessions, maxSessions, requestId, sessionId);

        if (currentSessions < maxSessions) {
            // 즉시 입장 - 활성 세션으로 등록
            // ✅ [수정] TTL 방식 대신 Sorted Set에 입장 시간 기록
            zSetOps.add(activeSessionsKey(type, id), requestId + ":" + sessionId, System.currentTimeMillis());

            logger.info("[{}] 즉시 입장 허가 - 현재 활성세션: {}/{}", getTotalActiveCount(type, id), maxSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장 허가되었습니다.", requestId, null, null);
        } else {
            // 대기열 등록
            setOps.add(WAITING_MOVIES, id);
            String waitingKey = waitingQueueKey(type, id);
            String member = requestId + ":" + sessionId;

            zSetOps.add(waitingKey, member, Instant.now().toEpochMilli());

            Long myRank = zSetOps.rank(waitingKey, member);
            myRank = (myRank == null) ? 0L : myRank;
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
        
        // ✅ [수정] 활성 세션(ZSet)에서 제거
        zSetOps.remove(activeSessionsKey(type, id), member);
        
        // 대기열(ZSet)에서 제거
        zSetOps.remove(waitingQueueKey(type, id), member);

        logger.info("[{}] 사용자 퇴장 처리 완료: {}:{}", id, requestId, sessionId);
    }

    /**
     * 대기열에서 다음 사용자들을 가져와 활성세션으로 승격
     */
    public List<String> admitNextUsers(String type, String id, long count) {
        String waitingKey = waitingQueueKey(type, id);
        String activeKey = activeSessionsKey(type, id);
        List<String> admittedUsers = new ArrayList<>();
        
        try {
            Set<String> waitingUserMembers = zSetOps.range(waitingKey, 0, count - 1);
            if (waitingUserMembers == null || waitingUserMembers.isEmpty()) {
                return admittedUsers;
            }
            
            for (String member : waitingUserMembers) {
                Long removed = zSetOps.remove(waitingKey, member);
                if (removed != null && removed > 0) {
                    // ✅ [수정] 활성 세션 ZSet에 현재 시간을 score로 하여 추가
                    zSetOps.add(activeKey, member, System.currentTimeMillis());
                    admittedUsers.add(member);
                    logger.info("[{}] 사용자 입장 처리 완료 - {} (대기열→활성세션)", id, member);
                }
            }
            
            logger.info("[{}] 대기열에서 {}명을 활성 세션으로 이동 완료", id, admittedUsers.size());
            return admittedUsers;
            
        } catch (Exception e) {
            logger.error("[{}] 대기열 처리 중 오류 발생", id, e);
            return admittedUsers;
        }
    }

    /**
     * 활성 세션 수 조회 (ZSet으로 변경)
     */
    public long getTotalActiveCount(String type, String id) {
        Long count = zSetOps.zCard(activeSessionsKey(type, id));
        return count != null ? count : 0;
    }

    // ✅ [신규 추가] 만료 시간 이전의 세션 조회
    public Set<String> findExpiredActiveSessions(String type, String id) {
        long expirationThreshold = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
        return zSetOps.rangeByScore(activeSessionsKey(type, id), 0, expirationThreshold);
    }

    // ✅ [신규 추가] 만료된 세션 일괄 삭제
    public void removeActiveSessions(String type, String id, Set<String> expiredMembers) {
        if (expiredMembers == null || expiredMembers.isEmpty()) return;
        zSetOps.remove(activeSessionsKey(type, id), expiredMembers.toArray(new String[0]));
    }
    
    // ... (나머지 메소드들은 기존과 동일)
    private String activeSessionsKey(String type, String id) {
        // ✅ 키 이름 변경 (활성 세션이 Sorted Set임을 명시)
        return "active_sessions_zset:" + type + ":" + id;
    }

    private String waitingQueueKey(String type, String id) {
        return "waiting_queue:" + type + ":" + id;
    }

    public boolean isUserInActiveSession(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        Double score = zSetOps.score(activeSessionsKey(type, id), member);
        return score != null;
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(waitingQueueKey(type, id));
        return count != null ? count : 0;
    }
    
    public Long getUserWaitingRank(String type, String id, String sessionId, String requestId) {
        String member = requestId + ":" + sessionId;
        return zSetOps.rank(waitingQueueKey(type, id), member);
    }
    
    public long getVacantSlots(String type, String id) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, id);
        return Math.max(0, maxSessions - currentSessions);
    }

    public Set<String> getActiveQueueMovieIds() {
        Set<String> waitingMovies = setOps.members(WAITING_MOVIES);
        return waitingMovies != null ? waitingMovies : Collections.emptySet();
    }
    
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingKey = waitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingKey, 0, -1);
        
        Map<String, Long> ranks = new HashMap<>();
        if (members != null) {
            long rank = 1;
            for (String member : members) {
                String[] parts = member.split(":", 2);
                if (parts.length >= 1) {
                    ranks.put(parts[0], rank);
                }
                rank++;
            }
        }
        return ranks;
    }
}