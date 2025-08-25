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

    @Value("${admission.max-active-sessions:2}")
    private long fallbackMaxActiveSessions;

    @Value("${admission.session-timeout-seconds:30}")
    private long sessionTimeoutSeconds;

    private final RedisTemplate<String, String> redisTemplate;
    private final com.example.admission.service.DynamicSessionCalculator sessionCalculator;

    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    public AdmissionService(RedisTemplate<String, String> redisTemplate,
                            com.example.admission.service.DynamicSessionCalculator sessionCalculator) {
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
        String activeSessionsKey = activeSessionsKey(type, id); // SET
        String waitingQueueKey   = waitingQueueKey(type, id);   // ZSET
        String activeUsersPrefix = activeUsersPrefix(type, id); // STRING TTL prefix
        String member = requestId + ":" + sessionId;

        long maxActiveSessions = sessionCalculator.calculateMaxActiveSessions();
        Long currentActiveSessions = setOps.size(activeSessionsKey);
        if (currentActiveSessions == null) currentActiveSessions = 0L;

        if (currentActiveSessions < maxActiveSessions) {
            // 즉시 입장
            setOps.add(activeSessionsKey, member);
            redisTemplate.opsForValue().set(activeUsersPrefix + member, "1", Duration.ofSeconds(sessionTimeoutSeconds));

            // 🔹 인덱스 갱신
            redisTemplate.opsForSet().add(ACTIVE_MOVIES, id);

            logger.info("[{}] 즉시 입장 성공: {}/{}", id, currentActiveSessions + 1, maxActiveSessions);
            return new EnterResponse(EnterResponse.Status.SUCCESS, "즉시 입장되었습니다.", requestId, null, null);
        } else {
            // 대기열 등록
            double score = Instant.now().toEpochMilli();
            zSetOps.add(waitingQueueKey, member, score);

            // 🔹 인덱스 갱신
            redisTemplate.opsForSet().add(WAITING_MOVIES, id);

            Long myRank = zSetOps.rank(waitingQueueKey, member);
            Long totalWaiting = zSetOps.zCard(waitingQueueKey);

            logger.info("[{}] 대기열 등록: 순위 {}/{}", id, myRank != null ? myRank + 1 : "?", totalWaiting);
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

    // 🔹 SCAN 제거: 인덱스 기반으로 활성 대기열 영화 목록 조회
    public Set<String> getActiveQueueMovieIds() {
        Set<String> ids = redisTemplate.opsForSet().members(WAITING_MOVIES);
        if (ids == null || ids.isEmpty()) return Collections.emptySet();

        // 인덱스 정합성 자가치유(옵션): 대기열이 비면 인덱스에서 제거
        Set<String> valid = new HashSet<>();
        for (String id : ids) {
            Long z = redisTemplate.opsForZSet().zCard(waitingQueueKey("movie", id));
            if (z != null && z > 0) {
                valid.add(id);
            } else {
                redisTemplate.opsForSet().remove(WAITING_MOVIES, id);
            }
        }
        return valid;
    }

    // 🔹 Lua 제거: 개별 명령으로 이동 + 인덱스 갱신
    public Map<String, String> admitUsersFromQueue(String type, String id, long count) {
        String waitingQueueKey = waitingQueueKey(type, id);
        String activeSessionsKey = activeSessionsKey(type, id);
        String activeUsersPrefix = activeUsersPrefix(type, id);

        try {
            if (count <= 0) return Collections.emptyMap();

            long end = count - 1;
            Set<String> membersToAdmit = zSetOps.range(waitingQueueKey, 0, end);
            if (membersToAdmit == null || membersToAdmit.isEmpty()) {
                return Collections.emptyMap();
            }

            Map<String, String> resultMap = new HashMap<>();
            List<String> admitted = new ArrayList<>();

            for (String member : membersToAdmit) {
                try {
                    setOps.add(activeSessionsKey, member);
                    redisTemplate.opsForValue().set(activeUsersPrefix + member, "1",
                            Duration.ofSeconds(sessionTimeoutSeconds));

                    int idx = member.indexOf(':');
                    if (idx > 0) {
                        String requestId = member.substring(0, idx);
                        String sessionId = member.substring(idx + 1);
                        resultMap.put(requestId, sessionId);
                    }
                    admitted.add(member);
                } catch (Exception e) {
                    logger.warn("[{}] 사용자 입장 처리 중 오류 ({}): {}", id, member, e.getMessage());
                }
            }

            if (!admitted.isEmpty()) {
                zSetOps.remove(waitingQueueKey, admitted.toArray());
                // 🔹 인덱스 갱신
                redisTemplate.opsForSet().add(ACTIVE_MOVIES, id);

                Long remain = zSetOps.zCard(waitingQueueKey);
                if (remain == null || remain == 0) {
                    redisTemplate.opsForSet().remove(WAITING_MOVIES, id);
                }

                logger.info("[{}] 대기열에서 {}명을 활성세션으로 이동 완료", id, admitted.size());
            }

            return resultMap;
        } catch (Exception e) {
            logger.error("대기열에서 사용자 입장 처리 중 오류 발생", e);
            return Collections.emptyMap();
        }
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

    public com.example.admission.service.DynamicSessionCalculator.SessionCalculationInfo getConfiguration() {
        return sessionCalculator.getCalculationInfo();
    }

    private String activeSessionsKey(String type, String id) {
        return "active_sessions:" + type + ":" + id;
    }

    private String waitingQueueKey(String type, String id) {
        return "waiting_queue:" + type + ":" + id;
    }

    private String activeUsersPrefix(String type, String id) {
        return "active_users:" + type + ":" + id + ":";
    }
}
