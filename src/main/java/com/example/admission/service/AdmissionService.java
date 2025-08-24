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

import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);

    // ★★★ 기존 고정값 설정을 fallback용으로만 사용 ★★★
    @Value("${admission.max-active-sessions:2}")
    private long fallbackMaxActiveSessions;

    private final RedisTemplate<String, String> redisTemplate;
    private final com.example.admission.service.DynamicSessionCalculator sessionCalculator; // ★ 새로 추가
    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate, 
                           com.example.admission.service.DynamicSessionCalculator sessionCalculator) { // ★ 생성자 수정
        this.redisTemplate = redisTemplate;
        this.sessionCalculator = sessionCalculator;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
        
        // 초기화 시 현재 설정 로깅
        logCurrentConfiguration();
    }

    private String getWaitingQueueKey(String type, String id) { 
        return "waiting_queue:" + type + ":" + id; 
    }
    
    private String getActiveSessionsKey(String type, String id) { 
        return "active_sessions:" + type + ":" + id; 
    }
    
    private String getActiveQueuesKey(String type) { 
        return "active_queues:" + type; 
    }

    /**
     * ★★★ 핵심 변경: 동적으로 최대 세션 수를 가져옴 ★★★
     */
    private long getMaxActiveSessions() {
        try {
            return sessionCalculator.calculateMaxActiveSessions();
        } catch (Exception e) {
            logger.error("동적 세션 계산 중 오류 발생, fallback 값({}) 사용", fallbackMaxActiveSessions, e);
            return fallbackMaxActiveSessions;
        }
    }

    public EnterResponse tryEnter(String type, String id, String sessionId, String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        // ★ 동적으로 계산된 빈 슬롯 수 확인
        if (getVacantSlots(type, id) > 0) {
            addToActiveSessions(type, id, sessionId, requestId);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장 처리되었습니다.", requestId, null, null);
        } else {
            String waitingQueueKey = getWaitingQueueKey(type, id);
            String activeQueuesKey = getActiveQueuesKey(type);
            String member = requestId + ":" + sessionId;
            
            zSetOps.add(waitingQueueKey, member, System.currentTimeMillis());
            setOps.add(activeQueuesKey, id);

            Long myRank = getUserRank(type, id, requestId);
            
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, myRank, getTotalWaitingCount(type, id));
        }
    }

    /**
     * ★ 동적 세션 수를 고려한 빈 슬롯 계산
     */
    public long getVacantSlots(String type, String id) {
        long maxSessions = getMaxActiveSessions(); // ★ 동적으로 계산
        long currentSessions = getCurrentActiveSessionsCount(type, id);
        long vacant = maxSessions - currentSessions;
        
        logger.debug("빈 슬롯 계산 - 최대: {}, 현재: {}, 빈 슬롯: {}", maxSessions, currentSessions, vacant);
        
        return Math.max(0, vacant);
    }

    public long getCurrentActiveSessionsCount(String type, String id) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        Long count = zSetOps.zCard(activeSessionsKey);
        return count != null ? count : 0;
    }

    public void addToActiveSessions(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String member = requestId + ":" + sessionId;
        zSetOps.add(activeSessionsKey, member, System.currentTimeMillis());
        
        logger.info("활성 세션 추가: {} -> {}", activeSessionsKey, member);
    }

    public Long getUserRank(String type, String id, String requestId) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        
        if (members != null) {
            int rank = 1;
            for (String member : members) {
                if (member.startsWith(requestId + ":")) {
                    return (long) rank;
                }
                rank++;
            }
        }
        return null;
    }

    /**
     * ✅ 수정: 대기열에서 다음 사용자들을 가져옴 (타입 안전성 확보)
     */
    public Map<String, String> popNextUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        Map<String, String> result = new HashMap<>();
        
        for (int i = 0; i < count; i++) {
            // ✅ range(0, 0)으로 첫 번째 요소만 가져오기
            Set<String> nextMembers = zSetOps.range(waitingQueueKey, 0, 0);
            if (nextMembers == null || nextMembers.isEmpty()) {
                break;
            }
            
            String member = nextMembers.iterator().next();
            // 먼저 제거한 후 처리
            Long removeCount = zSetOps.remove(waitingQueueKey, member);
            if (removeCount == null || removeCount == 0) {
                // 이미 다른 스레드에서 제거된 경우
                continue;
            }
            
            String[] parts = member.split(":", 2);
            if (parts.length == 2) {
                result.put(parts[0], parts[1]); // requestId -> sessionId
            }
        }
        
        logger.info("대기열에서 {}명을 추출했습니다: {}", result.size(), waitingQueueKey);
        return result;
    }

    /**
     * ★★★ 기존 코드에서 필요한 메서드들 추가 ★★★
     */
    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String waitingQueueKey = getWaitingQueueKey(type, id);
        String memberToRemove = requestId + ":" + sessionId;
        
        zSetOps.remove(activeSessionsKey, memberToRemove);
        zSetOps.remove(waitingQueueKey, memberToRemove);
        logger.info("[{}:{}] 세션 이탈: sessionId={}, requestId={}", type, id, sessionId, requestId);
    }

    public Set<String> getActiveQueues(String type) {
        return setOps.members(getActiveQueuesKey(type));
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(getWaitingQueueKey(type, id));
        return count != null ? count : 0;
    }

    public void removeQueueIfEmpty(String type, String id) {
        if (getTotalWaitingCount(type, id) == 0) {
            setOps.remove(getActiveQueuesKey(type), id);
        }
    }

    /**
     * ✅ 수정: 모든 사용자 순위 가져오기 (타입 안전성 확보)
     */
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        // ✅ 단순한 range() 사용 - Set<String> 반환
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        if (members == null || members.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Long> userRanks = new HashMap<>();
        long rank = 1;
        for (String member : members) {
            if (member.contains(":")) {
                String requestId = member.split(":")[0];
                userRanks.put(requestId, rank++);
            }
        }
        return userRanks;
    }

    /**
     * 현재 설정 정보를 로깅 (디버깅/모니터링용)
     */
    public void logCurrentConfiguration() {
        try {
            var info = sessionCalculator.getCalculationInfo();
            logger.info("=== Admission Service 현재 설정 ===");
            logger.info("Pod 수: {}", info.currentPodCount());
            logger.info("Pod당 기본 세션: {}", info.baseSessionsPerPod());
            logger.info("계산된 최대 세션: {}", info.calculatedMaxSessions());
            logger.info("최대 제한값: {}", info.maxTotalSessionsLimit());
            logger.info("동적 스케일링: {}", info.dynamicScalingEnabled() ? "활성화" : "비활성화");
            logger.info("Kubernetes 사용 가능: {}", info.kubernetesAvailable() ? "예" : "아니오 (fallback 모드)");
            logger.info("==============================");
        } catch (Exception e) {
            logger.error("설정 정보 로깅 중 오류", e);
        }
    }

    /**
     * API로 현재 설정을 확인할 수 있도록 public 메서드 제공
     */
    public com.example.admission.service.DynamicSessionCalculator.SessionCalculationInfo getCurrentConfiguration() {
        return sessionCalculator.getCalculationInfo();
    }
}