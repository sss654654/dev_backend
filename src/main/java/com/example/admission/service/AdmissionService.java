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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);

    @Value("${admission.max-active-sessions}")
    private long maxActiveSessions;

    private final RedisTemplate<String, String> redisTemplate;
    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        this.zSetOps = redisTemplate.opsForZSet();
        this.setOps = redisTemplate.opsForSet();
    }

    // --- 키 생성 로직 ---
    private String getWaitingQueueKey(String type, String id) { return "waiting_queue:" + type + ":" + id; }
    private String getActiveSessionsKey(String type, String id) { return "active_sessions:" + type + ":" + id; }
    private String getActiveQueuesKey(String type) { return "active_queues:" + type; }

    // --- 핵심 로직 ---
    public EnterResponse tryEnter(String type, String id, String sessionId, String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        if (getVacantSlots(type, id) > 0) {
            addToActiveSessions(type, id, sessionId, requestId);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장 처리되었습니다.", requestId, null);
        } else {
            String waitingQueueKey = getWaitingQueueKey(type, id);
            String activeQueuesKey = getActiveQueuesKey(type);
            String member = requestId + ":" + sessionId;
            zSetOps.add(waitingQueueKey, member, System.currentTimeMillis());
            setOps.add(activeQueuesKey, id);
            String waitUrl = "/wait.html?requestId=" + requestId;
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, waitUrl);
        }
    }

    // --- QueueProcessor가 사용할 메서드들 ---
    public Set<String> getActiveQueues(String type) {
        return setOps.members(getActiveQueuesKey(type));
    }
    
    public long getVacantSlots(String type, String id) {
        return Math.max(0, maxActiveSessions - getActiveUserCount(type, id));
    }
    
    public Map<String, String> popNextUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        Set<String> nextMembers = zSetOps.range(waitingQueueKey, 0, count - 1);
        if (nextMembers != null && !nextMembers.isEmpty()) {
            zSetOps.removeRange(waitingQueueKey, 0, count - 1);
            return nextMembers.stream()
                .filter(m -> m.contains(":"))
                .collect(Collectors.toMap(
                    m -> m.split(":")[0], // requestId
                    m -> m.split(":")[1]  // sessionId
                ));
        }
        return new HashMap<>();
    }
    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String waitingQueueKey = getWaitingQueueKey(type, id);

        // requestId와 sessionId를 기반으로 사용자를 특정하여 제거합니다.
        String memberToRemove = requestId + ":" + sessionId;
        
        // 활성 세션과 대기열 양쪽에서 모두 제거를 시도합니다.
        zSetOps.remove(activeSessionsKey, memberToRemove);
        zSetOps.remove(waitingQueueKey, memberToRemove);

        logger.info("[{}:{}] 세션 이탈: sessionId={}, requestId={}", type, id, sessionId, requestId);
    }
    public void addToActiveSessions(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String member = requestId + ":" + sessionId;
        zSetOps.add(activeSessionsKey, member, System.currentTimeMillis());
    }

    public Map<String, String> getWaitingUsers(String type, String id) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        if (members == null) return new HashMap<>();
        return members.stream()
            .filter(m -> m.contains(":"))
            .collect(Collectors.toMap(m -> m.split(":")[0], m -> m.split(":")[1]));
    }
    
    public void removeQueueIfEmpty(String type, String id) {
        if (getTotalWaitingCount(type, id) == 0) {
            setOps.remove(getActiveQueuesKey(type), id);
        }
    }

    // --- 공용 메서드 ---
    public long getActiveUserCount(String type, String id) {
        return zSetOps.zCard(getActiveSessionsKey(type, id));
    }

    public long getTotalWaitingCount(String type, String id) {
        return zSetOps.zCard(getWaitingQueueKey(type, id));
    }

    public Long getUserRank(String type, String id, String requestId) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        if (members == null) return null;
        String targetMember = members.stream()
            .filter(m -> m.startsWith(requestId + ":"))
            .findFirst().orElse(null);
        return targetMember != null ? zSetOps.rank(waitingQueueKey, targetMember) : null;
    }
}