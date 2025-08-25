package com.example.admission.service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import com.example.admission.dto.EnterResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);

    @Value("${admission.max-active-sessions:2}")
    private long fallbackMaxActiveSessions;

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
        logCurrentConfiguration();
    }

    public EnterResponse tryEnter(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = "active_sessions:" + type + ":" + id;
        String waitingQueueKey = "waiting_queue:" + type + ":" + id;
        String activeUsersKeyPrefix = "active_users:" + type + ":" + id + ":";
        String member = requestId + ":" + sessionId;

        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        Long currentActiveSessions = setOps.size(activeSessionsKey);
        if (currentActiveSessions == null) currentActiveSessions = 0L;

        if (currentActiveSessions < maxActiveSessions) {
            setOps.add(activeSessionsKey, member);
            redisTemplate.opsForValue().set(activeUsersKeyPrefix + member, "1", Duration.ofSeconds(sessionTimeoutSeconds));
            
            logger.info("[{}] 즉시 입장 성공: {}/{}", id, currentActiveSessions + 1, maxActiveSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            double score = Instant.now().toEpochMilli();
            zSetOps.add(waitingQueueKey, member, score);
            Long myRank = zSetOps.rank(waitingQueueKey, member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey);

            logger.info("[{}] 대기열 등록: 순위 {}/{}", id, myRank != null ? myRank + 1 : "?", totalWaiting);
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.", requestId, myRank != null ? myRank + 1 : null, totalWaiting);
        }
    }

    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = "active_sessions:" + type + ":" + id;
        String waitingQueueKey = "waiting_queue:" + type + ":" + id;
        String member = requestId + ":" + sessionId;

        if (Boolean.TRUE.equals(setOps.isMember(activeSessionsKey, member))) {
            setOps.remove(activeSessionsKey, member);
            logger.info("[{}] 활성 세션에서 퇴장: {}", id, member);
        } else if (zSetOps.score(waitingQueueKey, member) != null) {
            zSetOps.remove(waitingQueueKey, member);
            logger.info("[{}] 대기열에서 퇴장: {}", id, member);
        }
    }

    public long getTotalWaitingCount(String type, String id) {
        String waitingQueueKey = "waiting_queue:" + type + ":" + id;
        Long count = zSetOps.zCard(waitingQueueKey);
        return count != null ? count : 0;
    }

    public long getActiveSessionCount(String type, String id) {
        String activeSessionsKey = "active_sessions:" + type + ":" + id;
        Long count = setOps.size(activeSessionsKey);
        return count != null ? count : 0;
    }
    
    public long getVacantSlots(String type, String id) {
        long max = sessionCalculator.calculateMaxActiveSessions();
        long current = getActiveSessionCount(type, id);
        return Math.max(0, max - current);
    }
    
    // AdmissionService.java의 getActiveQueueMovieIds() 메서드를 다음과 같이 수정하세요:

    public Set<String> getActiveQueueMovieIds() {
    Set<String> movieIds = new HashSet<>();
    try {
        redisTemplate.execute((RedisConnection connection) -> {
            try {
                ScanOptions options = ScanOptions.scanOptions()
                        .match("waiting_queue:movie:*")
                        .count(50)
                        .build();
                
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        try {
                            String key = new String(cursor.next(), StandardCharsets.UTF_8);
                            if (key.startsWith("waiting_queue:movie:")) {
                                String movieId = key.substring("waiting_queue:movie:".length());
                                if (!movieId.isEmpty()) {
                                    movieIds.add(movieId);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("Redis SCAN 키 처리 중 오류: {}", e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Redis SCAN cursor 처리 중 오류", e);
                }
            } catch (Exception e) {
                logger.error("Redis SCAN 옵션 설정 중 오류", e);
            }
            return null;
        });
    } catch (Exception e) {
        logger.error("Redis connection 실행 중 오류", e);
        // 폴백: 하드코딩된 movieIds 반환
        movieIds.add("movie-avatar3");
        movieIds.add("movie1");
        logger.warn("SCAN 실패로 폴백 영화 목록 사용: {}", movieIds);
    }
    
    logger.debug("활성 대기열 영화 IDs: {}", movieIds);
    return movieIds;
}
        
    // AdmissionService.java의 admitUsersFromQueue() 메서드를 다음과 같이 교체하세요:

    public Map<String, String> admitUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = "waiting_queue:" + type + ":" + id;
        String activeSessionsKey = "active_sessions:" + type + ":" + id;
        String activeUsersKeyPrefix = "active_users:" + type + ":" + id + ":";

        try {
            // ✅ CROSSSLOT 오류 해결: Lua 스크립트 대신 개별 Redis 명령 사용
            
            // 1. 대기열에서 입장할 사용자들을 가져옴
            Set<String> membersToAdmit = zSetOps.range(waitingQueueKey, 0, count - 1);
            
            if (membersToAdmit == null || membersToAdmit.isEmpty()) {
                return Collections.emptyMap();
            }
            
            Map<String, String> resultMap = new HashMap<>();
            List<String> admittedMembers = new ArrayList<>();
            
            // 2. 각 사용자를 개별적으로 처리
            for (String member : membersToAdmit) {
                try {
                    // 활성 세션에 추가
                    setOps.add(activeSessionsKey, member);
                    
                    // 타임아웃 키 설정
                    redisTemplate.opsForValue().set(
                        activeUsersKeyPrefix + member, 
                        "1", 
                        Duration.ofSeconds(sessionTimeoutSeconds)
                    );
                    
                    // 결과 맵에 추가
                    String[] parts = member.split(":", 2);
                    if (parts.length == 2) {
                        resultMap.put(parts[0], parts[1]); // requestId -> sessionId
                    }
                    
                    admittedMembers.add(member);
                    
                } catch (Exception e) {
                    logger.warn("사용자 입장 처리 중 오류 ({}): {}", member, e.getMessage());
                }
            }
            
            // 3. 성공적으로 처리된 사용자들을 대기열에서 제거
            if (!admittedMembers.isEmpty()) {
                zSetOps.remove(waitingQueueKey, admittedMembers.toArray());
                logger.info("[{}] 대기열에서 {}명을 활성세션으로 이동 완료", id, admittedMembers.size());
            }
            
            return resultMap;
            
        } catch (Exception e) {
            logger.error("대기열에서 사용자 입장 처리 중 오류 발생", e);
            return Collections.emptyMap();
        }
    }
    
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = "waiting_queue:" + type + ":" + id;
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

    public DynamicSessionCalculator.SessionCalculationInfo getConfiguration() {
        return sessionCalculator.getCalculationInfo();
    }
}