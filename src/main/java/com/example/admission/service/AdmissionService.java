package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import com.example.admission.service.DynamicSessionCalculator;
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

    @Value("${admission.session.timeout:300}")
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
            String activeKey = activeSessionsKey(type, id);
            String member = requestId + ":" + sessionId;
            setOps.add(activeKey, member);

            // 활성 세션에 TTL 설정
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));

            logger.info("[{}] 즉시 입장 허가 - 현재 활성세션: {}/{}", id, currentSessions + 1, maxSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장 허가되었습니다.", requestId, null, null);
        } else {
            // 대기열 등록
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
        List<String> admittedUsers = new ArrayList<>();
        
        try {
            // 대기열에서 count만큼 사용자를 꺼내기 (FIFO 순서)
            Set<ZSetOperations.TypedTuple<String>> waitingUsers = 
                zSetOps.rangeWithScores(waitingKey, 0, count - 1);
                
            if (waitingUsers == null || waitingUsers.isEmpty()) {
                return admittedUsers;
            }
            
            long currentTime = System.currentTimeMillis();
            
            for (ZSetOperations.TypedTuple<String> user : waitingUsers) {
                String member = user.getValue();  // "requestId:sessionId"
                if (member == null) continue;
                
                // ✅ 핵심 수정: 대기열에서 제거 + 활성세션에 추가를 원자적으로 수행
                Long removed = zSetOps.remove(waitingKey, member);
                if (removed != null && removed > 0) {
                    // 활성 세션에 추가
                    setOps.add(activeKey, member);
                    
                    // ✅ 중요: 활성 세션 TTL 설정 (30초)
                    String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
                    redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));
                    
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
    

    public boolean isUserInActiveSession(String type, String id, String sessionId, String requestId) {
    String member = requestId + ":" + sessionId;
    Boolean isMember = setOps.isMember(activeSessionsKey(type, id), member);
    return Boolean.TRUE.equals(isMember);
}

// ✅ 새로 추가할 메서드들 (중복되지 않는 것만)
/**
 * 사용자 세션 연장 (새 메서드)
    */
    public boolean extendUserSession(String type, String id, String requestId, String sessionId) {
        try {
            String member = requestId + ":" + sessionId;
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            
            // 현재 세션이 활성 상태인지 확인
            if (isUserInActiveSession(type, id, sessionId, requestId)) {
                // TTL 30초 연장
                redisTemplate.opsForValue().set(timeoutKey, "1", Duration.ofSeconds(sessionTimeoutSeconds));
                
                logger.info("[{}] 사용자 세션 연장 완료 - member: {}, 연장시간: {}초", 
                        id, member, sessionTimeoutSeconds);
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            logger.error("[{}] 세션 연장 실패 - requestId: {}", id, requestId, e);
            return false;
        }
    }

    /**
     * 활성세션 상태를 TTL과 함께 확인하는 새 메서드
     */
    public boolean isUserActiveWithTTL(String type, String id, String requestId, String sessionId) {
        try {
            // 먼저 Set에 있는지 확인
            if (!isUserInActiveSession(type, id, sessionId, requestId)) {
                return false;
            }
            
            // TTL도 함께 확인
            String member = requestId + ":" + sessionId;
            String timeoutKey = "active_user_ttl:" + type + ":" + id + ":" + member;
            Long ttl = redisTemplate.getExpire(timeoutKey);
            
            boolean isActive = (ttl != null && ttl > 0);
            
            logger.debug("[{}] 사용자 활성세션 상태 - member: {}, ttl: {}s, isActive: {}", 
                        id, member, ttl, isActive);
                        
            return isActive;
            
        } catch (Exception e) {
            logger.error("[{}] 활성세션 상태 확인 실패 - requestId: {}", id, requestId, e);
            return false;
        }
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