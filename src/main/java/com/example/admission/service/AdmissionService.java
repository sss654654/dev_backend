
package com.example.admission.service;

import com.example.admission.dto.EnterResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;

    @Service
    public class AdmissionService {

        private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);

        // --- Redis Key Prefixes ---
        private static final String WAITING_QUEUE_PREFIX = "waiting_queue:";
        private static final String ACTIVE_USERS_PREFIX = "active_users:";
        private static final String SESSION_MAP_PREFIX = "session_map:";
        private static final String METRICS_PREFIX = "queue_metrics:";
        private static final String SEQUENCE_PREFIX = "sequence:";

        @Value("${admission.max-active-sessions}")
        private long maxActiveSessions;

        private final RedisTemplate<String, String> redisTemplate;
        private ZSetOperations<String, String> zSetOps;
        private HashOperations<String, String, String> hashOps;
        private ValueOperations<String, String> valueOps;

        public AdmissionService(RedisTemplate<String, String> redisTemplate) {
            this.redisTemplate = redisTemplate;
        }

        @PostConstruct
        public void init() {
            this.zSetOps = redisTemplate.opsForZSet();
            this.hashOps = redisTemplate.opsForHash();
            this.valueOps = redisTemplate.opsForValue();
        }

        public EnterResult tryEnterMovie(String sessionId, String requestId, String movieId) {
            return handleAdmission("movie", movieId, sessionId, requestId);
        }

        public EnterResult tryEnterCoupon(String sessionId, String requestId) {
            return handleAdmission("coupon", "global", sessionId, requestId);
        }

        private EnterResult handleAdmission(String type, String id, String sessionId, String requestId) {
            String queueIdentifier = type + ":" + id;
            String waitingQueueKey = WAITING_QUEUE_PREFIX + queueIdentifier;
            String activeUsersKey = ACTIVE_USERS_PREFIX + queueIdentifier;
            String sessionMapKey = SESSION_MAP_PREFIX + sessionId;
            String metricsKey = METRICS_PREFIX + queueIdentifier;
            String sequenceKey = SEQUENCE_PREFIX + queueIdentifier;

            String oldRequestId = hashOps.get(sessionMapKey, queueIdentifier);
            if (oldRequestId != null) {
                zSetOps.remove(activeUsersKey, oldRequestId);
                zSetOps.remove(waitingQueueKey, oldRequestId);
            }

            if (getVacantSlots(type, id) > 0) {
                zSetOps.add(activeUsersKey, requestId, System.currentTimeMillis());
                hashOps.put(sessionMapKey, queueIdentifier, requestId);
                return EnterResult.success();
            } else {
                Long mySeq = valueOps.increment(sequenceKey);
                if (mySeq == null) mySeq = -1L;

                zSetOps.add(waitingQueueKey, requestId, mySeq);
                hashOps.put(sessionMapKey, queueIdentifier, requestId);
                hashOps.increment(metricsKey, "total_accumulated", 1);

                Long myRank = zSetOps.rank(waitingQueueKey, requestId);
                myRank = (myRank != null) ? myRank + 1 : 1L;

                Long totalWaiting = zSetOps.zCard(waitingQueueKey);

                String headSeqStr = hashOps.get(metricsKey, "last_admitted_seq");
                Long headSeq = (headSeqStr != null) ? Long.parseLong(headSeqStr) : 0L;

                return EnterResult.queued(myRank, mySeq, headSeq, totalWaiting);
            }
        }

        /**
         * (스케줄러용) 대기열에서 다음 사용자들을 '배치'로 한번에 입장시킵니다.
         * @param count 입장시킬 최대 인원 수
         * @return 실제로 입장 처리된 사용자들의 정보 Set
         */
        public Set<ZSetOperations.TypedTuple<String>> admitNextUsers(String type, String id, long count) {
            String queueIdentifier = type + ":" + id;
            String waitingQueueKey = WAITING_QUEUE_PREFIX + queueIdentifier;
            String activeUsersKey = ACTIVE_USERS_PREFIX + queueIdentifier;
            String metricsKey = METRICS_PREFIX + queueIdentifier;

            // 1. 대기열에서 최대 count만큼 사용자들을 '한번에' 꺼내옵니다. (Redis 통신 1번)
            Set<ZSetOperations.TypedTuple<String>> admittedUsers = zSetOps.popMin(waitingQueueKey, count);
            if (admittedUsers == null || admittedUsers.isEmpty()) {
                return Set.of();
            }

            long lastAdmittedSeq = -1L;
            long currentTime = System.currentTimeMillis();

            // 2. 꺼내온 사용자들을 '한번에' 활성 목록에 추가합니다. (Redis 통신 1번)
            for (ZSetOperations.TypedTuple<String> user : admittedUsers) {
                String requestId = user.getValue();
                if (requestId != null) {
                    // RedisTemplate은 내부적으로 파이프라이닝을 통해 이 과정의 성능을 최적화합니다.
                    zSetOps.add(activeUsersKey, requestId, currentTime);

                    if (user.getScore() != null) {
                        lastAdmittedSeq = Math.max(lastAdmittedSeq, user.getScore().longValue());
                    }
                }
            }

            // 3. 가장 마지막 순번을 metrics에 '한번' 기록합니다. (Redis 통신 1번)
            if (lastAdmittedSeq != -1L) {
                hashOps.put(metricsKey, "last_admitted_seq", String.valueOf(lastAdmittedSeq));
            }

            logger.info("[{}] {}명의 사용자를 활성 세션으로 이동시켰습니다. 마지막 처리 순번: {}", queueIdentifier, admittedUsers.size(), lastAdmittedSeq);
            return admittedUsers;
        }

        public void leave(String type, String id, String sessionId) {
            String queueIdentifier = type + ":" + id;
            String sessionMapKey = SESSION_MAP_PREFIX + sessionId;
            String requestId = hashOps.get(sessionMapKey, queueIdentifier);

            if (requestId != null) {
                zSetOps.remove(ACTIVE_USERS_PREFIX + queueIdentifier, requestId);
                zSetOps.remove(WAITING_QUEUE_PREFIX + queueIdentifier, requestId);
                hashOps.delete(sessionMapKey, queueIdentifier);
            }
        }

        public long getVacantSlots(String type, String id) {
            return Math.max(0, maxActiveSessions - getActiveUserCount(type, id));
        }

        public long getActiveUserCount(String type, String id) {
            String activeUsersKey = ACTIVE_USERS_PREFIX + type + ":" + id;
            Long count = zSetOps.zCard(activeUsersKey);
            return count != null ? count : 0;
        }

        // ★★★★★ 누락되었던 메소드들 다시 추가 ★★★★★

        /**
         * 특정 사용자의 현재 대기 순위를 반환합니다. (0-based)
         * @param type 큐 타입 (e.g., "movie")
         * @param id 큐 ID (e.g., "123")
         * @param requestId 순위를 조회할 사용자의 요청 ID
         * @return 사용자의 대기 순위. 대기열에 없으면 null.
         */
        public Long getUserRank(String type, String id, String requestId) {
            String waitingQueueKey = WAITING_QUEUE_PREFIX + type + ":" + id;
            return zSetOps.rank(waitingQueueKey, requestId);
        }

        /**
         * 특정 큐의 총 대기자 수를 반환합니다.
         * @param type 큐 타입
         * @param id 큐 ID
         * @return 총 대기자 수
         */
        public long getTotalWaitingCount(String type, String id) {
            String waitingQueueKey = WAITING_QUEUE_PREFIX + type + ":" + id;
            Long count = zSetOps.zCard(waitingQueueKey);
            return count != null ? count : 0;
        }
}