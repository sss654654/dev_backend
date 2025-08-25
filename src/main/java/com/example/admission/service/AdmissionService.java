package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class AdmissionService {
    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);
    
    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;
    
    // Redis Keys
    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";
    private static final String SESSION_EXPIRY_KEY = "session_expiry:";

    public AdmissionService(RedisTemplate<String, String> redisTemplate,
                          DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    /**
     * 🔹 핵심 메서드: enter (기존 tryEnter에서 이름 변경)
     */
    public EnterResponse enter(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;

        // 1. 현재 활성 세션 수 정확히 계산
        long currentActiveSessions = getCurrentActiveSessionCount(activeSessionsKey);
        long maxSessions = sessionCalculator.calculateMaxActiveSessions(); // ✅ 올바른 메서드명
        
        logger.info("[{}] 입장 요청 - 현재 활성세션: {}/{}, 요청자: {}", 
                   id, currentActiveSessions, maxSessions, requestId);

        // 2. 정확히 빈자리가 있을 때만 즉시 입장
        if (currentActiveSessions < maxSessions) {
            // 즉시 입장 허가
            setOps.add(activeSessionsKey, member);
            
            // 세션 만료 시간 설정 (30초)
            String sessionExpiryKey = SESSION_EXPIRY_KEY + requestId;
            redisTemplate.opsForValue().set(sessionExpiryKey, 
                String.valueOf(System.currentTimeMillis() + 30000), 30, TimeUnit.SECONDS);
            
            redisTemplate.opsForSet().add(ACTIVE_MOVIES, id);
            
            logger.info("[{}] 즉시 입장 허가 - 활성세션: {}/{}", 
                       id, currentActiveSessions + 1, maxSessions);
            
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장이 허가되었습니다.",
                    requestId, null, null);
        } else {
            // 대기열 등록
            double score = Instant.now().toEpochMilli();
            zSetOps.add(waitingQueueKey, member, score);
            redisTemplate.opsForSet().add(WAITING_MOVIES, id);

            Long myRank = zSetOps.rank(waitingQueueKey, member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey);

            logger.info("[{}] 대기열 등록 - 순위: {}/{}, 활성세션 포화상태: {}/{}", 
                       id, myRank != null ? myRank + 1 : "?", totalWaiting, currentActiveSessions, maxSessions);
            
            return new EnterResponse(EnterResponse.Status.QUEUED, "대기열에 등록되었습니다.",
                    requestId, myRank != null ? myRank + 1 : null, totalWaiting);
        }
    }

    /**
     * 🔹 정확한 활성 세션 수 계산 (만료된 세션 자동 제거)
     */
    private long getCurrentActiveSessionCount(String activeSessionsKey) {
        Set<String> allMembers = setOps.members(activeSessionsKey);
        if (allMembers == null || allMembers.isEmpty()) {
            return 0;
        }

        long validSessionCount = 0;
        Set<String> expiredMembers = new HashSet<>();
        
        for (String member : allMembers) {
            String[] parts = member.split(":");
            if (parts.length >= 1) {
                String requestId = parts[0];
                String sessionExpiryKey = SESSION_EXPIRY_KEY + requestId;
                
                // 세션 만료 확인
                String expiryTime = redisTemplate.opsForValue().get(sessionExpiryKey);
                if (expiryTime == null || System.currentTimeMillis() > Long.parseLong(expiryTime)) {
                    // 만료된 세션 - 제거 대상에 추가
                    expiredMembers.add(member);
                    logger.info("만료된 세션 발견: {}", requestId);
                } else {
                    validSessionCount++;
                }
            }
        }
        
        // 만료된 세션들을 Redis에서 제거
        if (!expiredMembers.isEmpty()) {
            setOps.remove(activeSessionsKey, expiredMembers.toArray());
            logger.info("만료된 세션 {}개 정리 완료", expiredMembers.size());
        }
        
        return validSessionCount;
    }

    /**
     * 🔹 배치 단위로 대기열에서 사용자 입장 처리
     */
    public Map<String, String> admitUsersFromQueue(String type, String id, long batchSize) {
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey = waitingQueueKey(type, id);
        
        Map<String, String> resultMap = new HashMap<>();
        
        try {
            // 1. 현재 실제 빈 자리 수 확인
            long currentActive = getCurrentActiveSessionCount(activeSessionsKey);
            long maxSessions = sessionCalculator.calculateMaxActiveSessions(); // ✅ 올바른 메서드명
            long actualVacantSlots = maxSessions - currentActive;
            
            if (actualVacantSlots <= 0) {
                logger.debug("[{}] 빈자리 없음 - 현재: {}/{}", id, currentActive, maxSessions);
                return Collections.emptyMap();
            }
            
            // 2. 실제 처리 가능한 수만큼만 대기열에서 가져오기
            long processCount = Math.min(batchSize, Math.min(actualVacantSlots, 
                                                           zSetOps.zCard(waitingQueueKey)));
            
            if (processCount <= 0) {
                return Collections.emptyMap();
            }

            // 3. 대기열에서 순서대로 사용자 선택
            Set<String> candidates = zSetOps.range(waitingQueueKey, 0, processCount - 1);
            if (candidates == null || candidates.isEmpty()) {
                return Collections.emptyMap();
            }

            List<String> admitted = new ArrayList<>();
            
            // 4. 각 사용자를 활성 세션으로 이동
            for (String member : candidates) {
                String[] parts = member.split(":");
                if (parts.length >= 2) {
                    String requestId = parts[0];
                    String sessionId = parts[1];
                    
                    // 활성 세션에 추가
                    setOps.add(activeSessionsKey, member);
                    
                    // 세션 만료 시간 설정
                    String sessionExpiryKey = SESSION_EXPIRY_KEY + requestId;
                    redisTemplate.opsForValue().set(sessionExpiryKey, 
                        String.valueOf(System.currentTimeMillis() + 30000), 30, TimeUnit.SECONDS);
                    
                    admitted.add(member);
                    resultMap.put(requestId, sessionId);
                }
            }

            // 5. 대기열에서 제거
            if (!admitted.isEmpty()) {
                zSetOps.remove(waitingQueueKey, admitted.toArray());
                redisTemplate.opsForSet().add(ACTIVE_MOVIES, id);

                Long remain = zSetOps.zCard(waitingQueueKey);
                if (remain == null || remain == 0) {
                    redisTemplate.opsForSet().remove(WAITING_MOVIES, id);
                }

                logger.info("[{}] 대기열에서 {}명을 활성세션으로 이동 완료 - 현재 활성: {}/{}", 
                           id, admitted.size(), getCurrentActiveSessionCount(activeSessionsKey), maxSessions);
            }

            return resultMap;
        } catch (Exception e) {
            logger.error("대기열에서 사용자 입장 처리 중 오류 발생", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 빈 자리 수 정확히 계산
     */
    public long getVacantSlots(String type, String id) {
        long currentActive = getCurrentActiveSessionCount(activeSessionsKey(type, id));
        long maxSessions = sessionCalculator.calculateMaxActiveSessions(); // ✅ 올바른 메서드명
        return Math.max(0, maxSessions - currentActive);
    }

    /**
     * 사용자 퇴장 처리
     */
    public void leave(String type, String id, String sessionId, String requestId) {
        String activeSessionsKey = activeSessionsKey(type, id);
        String waitingQueueKey = waitingQueueKey(type, id);
        String member = requestId + ":" + sessionId;

        // 세션 만료 키도 함께 삭제
        String sessionExpiryKey = SESSION_EXPIRY_KEY + requestId;
        redisTemplate.delete(sessionExpiryKey);

        if (Boolean.TRUE.equals(setOps.isMember(activeSessionsKey, member))) {
            setOps.remove(activeSessionsKey, member);
            logger.info("[{}] 활성 세션에서 퇴장: {}", id, member);

            Long remain = setOps.size(activeSessionsKey);
            if (remain == null || remain == 0) {
                redisTemplate.opsForSet().remove(ACTIVE_MOVIES, id);
            }
        } else if (zSetOps.score(waitingQueueKey, member) != null) {
            zSetOps.remove(waitingQueueKey, member);
            logger.info("[{}] 대기열에서 퇴장: {}", id, member);

            Long waitingRemain = zSetOps.zCard(waitingQueueKey);
            if (waitingRemain == null || waitingRemain == 0) {
                redisTemplate.opsForSet().remove(WAITING_MOVIES, id);
            }
        }
    }

    /**
     * 모든 대기 중인 사용자의 순위 조회
     */
    public Map<String, Long> getAllUserRanks(String type, String id) {
        String waitingQueueKey = waitingQueueKey(type, id);
        Set<String> members = zSetOps.range(waitingQueueKey, 0, -1);
        if (members == null || members.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, Long> userRanks = new HashMap<>();
        long rank = 1;
        for (String member : members) {
            int idx = member.indexOf(':');
            if (idx > 0) {
                String requestId = member.substring(0, idx);
                userRanks.put(requestId, rank++);
            }
        }
        return userRanks;
    }

    public long getTotalWaitingCount(String type, String id) {
        Long count = zSetOps.zCard(waitingQueueKey(type, id));
        return count != null ? count : 0;
    }

    public long getActiveSessionCount(String type, String id) {
        return getCurrentActiveSessionCount(activeSessionsKey(type, id));
    }

    /**
     * 🔹 새로운 메서드: 대기열이 있는 모든 영화 ID 조회
     */
    public Set<String> getActiveQueueMovieIds() {
        Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
        return waitingMovies != null ? waitingMovies : Collections.emptySet();
    }

    private String activeSessionsKey(String type, String id) {
        return "active_sessions:" + type + ":" + id;
    }

    private String waitingQueueKey(String type, String id) {
        return "waiting_queue:" + type + ":" + id;
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