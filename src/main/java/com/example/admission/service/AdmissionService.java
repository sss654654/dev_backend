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

    private String getWaitingQueueKey(String type, String id) { return "waiting_queue:" + type + ":" + id; }
    private String getActiveSessionsKey(String type, String id) { return "active_sessions:" + type + ":" + id; }
    private String getActiveQueuesKey(String type) { return "active_queues:" + type; }

    public EnterResponse tryEnter(String type, String id, String sessionId, String requestId) {
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

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
    
    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String waitingQueueKey = getWaitingQueueKey(type, id);
        String memberToRemove = requestId + ":" + sessionId;
        
        zSetOps.remove(activeSessionsKey, memberToRemove);
        zSetOps.remove(waitingQueueKey, memberToRemove);
        logger.info("[{}:{}] 세션 이탈: sessionId={}, requestId={}", type, id, sessionId, requestId);
    }
    
    // ★ 순위 정보를 한번에 가져오는 효율적인 메서드 추가
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
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
        return Collections.emptyMap();
    }
    
    public void addToActiveSessions(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = getActiveSessionsKey(type, id);
        String member = requestId + ":" + sessionId;
        zSetOps.add(activeSessionsKey, member, System.currentTimeMillis());
    }
    
    public void removeQueueIfEmpty(String type, String id) {
        if (getTotalWaitingCount(type, id) == 0) {
            setOps.remove(getActiveQueuesKey(type), id);
        }
    }

    public long getActiveUserCount(String type, String id) {
        Long count = zSetOps.zCard(getActiveSessionsKey(type, id));
        return count != null ? count : 0;
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(getWaitingQueueKey(type, id));
        return count != null ? count : 0;
    }

    public Long getUserRank(String type, String id, String requestId) {
        String waitingQueueKey = getWaitingQueueKey(type, id);
        // requestId로 시작하는 멤버를 찾아야 함
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        if (members == null) return null;
        
        Optional<String> targetMember = members.stream()
            .filter(m -> m.startsWith(requestId + ":"))
            .findFirst();

        return targetMember.map(member -> zSetOps.rank(waitingQueueKey, member) + 1).orElse(null);
    }
}