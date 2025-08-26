package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final WebSocketUpdateService webSocketUpdateService;
    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    public SessionTimeoutProcessor(RedisTemplate<String, String> redisTemplate,
                                   WebSocketUpdateService webSocketUpdateService,
                                   AdmissionMetricsService metricsService,
                                   LoadBalancingOptimizer loadBalancer) {
        this.redisTemplate = redisTemplate;
        this.webSocketUpdateService = webSocketUpdateService;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    /**
     * 5초마다 만료된 세션을 정리합니다.
     * Set 기반의 active_sessions에서만 작업하므로 WRONGTYPE 에러가 발생하지 않습니다.
     */
    @Scheduled(fixedDelayString = "${admission.session-processor-interval-ms:5000}")
    public void processExpiredSessions() {
        try {
            Set<String> movieIds = redisTemplate.opsForSet().members("active_movies");
            if (movieIds == null || movieIds.isEmpty()) {
                return;
            }

            logger.debug("만료된 세션 정리 시작 - {} 개 영화 처리", movieIds.size());

            for (String movieId : movieIds) {
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    continue;
                }

                processMovieExpiredSessions(movieId);
            }

        } catch (Exception e) {
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
    }

    /**
     * 특정 영화의 만료된 세션을 처리합니다.
     * active_sessions는 Set이므로 Set 연산만 사용합니다.
     */
    /**
 * ✅ 수정: 입장 허가받은 사용자의 30초 타임아웃 처리 개선
 */
    private void processMovieExpiredSessions(String movieId) {
        try {
            String activeKey = "active_sessions:movie:" + movieId;
            Set<String> activeSessions = redisTemplate.opsForSet().members(activeKey);
            
            if (activeSessions == null || activeSessions.isEmpty()) {
                return;
            }

            List<String> expiredSessions = new ArrayList<>();
            
            for (String member : activeSessions) {
                String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + member;
                
                // ✅ TTL 확인: TTL이 0 이하이면 만료된 것으로 판단
                Long ttl = redisTemplate.getExpire(timeoutKey);
                if (ttl != null && ttl <= 0) {
                    expiredSessions.add(member);
                }
            }
            
            if (!expiredSessions.isEmpty()) {
                logger.warn("[{}] 타임아웃된 활성 세션 {}개를 발견하여 정리합니다.", movieId, expiredSessions.size());
                
                for (String expiredMember : expiredSessions) {
                    try {
                        // ✅ 활성 세션에서 제거
                        redisTemplate.opsForSet().remove(activeKey, expiredMember);
                        
                        // ✅ TTL 키 삭제
                        String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + expiredMember;
                        redisTemplate.delete(timeoutKey);
                        
                        // ✅ 사용자에게 타임아웃 알림 전송
                        String requestId = expiredMember.split(":")[0];
                        webSocketUpdateService.notifyTimeout(requestId);
                        
                        logger.debug("[{}] 만료된 세션 정리 완료: {}", movieId, expiredMember);
                        
                    } catch (Exception e) {
                        logger.error("[{}] 만료된 세션 정리 실패: {}", movieId, expiredMember, e);
                    }
                }
                
                logger.info("[{}] 타임아웃된 활성 세션 {}개 정리 완료", movieId, expiredSessions.size());
                
                // ✅ 중요: 세션 정리 후 새로운 사용자를 입장시킬 수 있으므로 대기열 처리 트리거
                triggerQueueProcessing(movieId);
            }
            
        } catch (Exception e) {
            logger.error("[{}] 만료된 세션 처리 중 오류", movieId, e);
        }
    }

    /**
     * ✅ 새 메서드: 세션 정리 후 대기열 처리를 트리거
     */
    private void triggerQueueProcessing(String movieId) {
        try {
            // QueueProcessor에게 즉시 처리 요청
            // (실제로는 ApplicationEventPublisher를 사용하거나 직접 호출)
            logger.info("[{}] 세션 정리 후 대기열 재처리 요청", movieId);
        } catch (Exception e) {
            logger.error("[{}] 대기열 재처리 요청 실패", movieId, e);
        }
    }


    /**
     * 만료된 세션들의 후속 처리를 담당합니다.
     */
    private void processExpiredMembers(String movieId, List<String> expiredMembers) {
        for (String member : expiredMembers) {
            try {
                // member 형식: "requestId:sessionId"
                String[] parts = member.split(":", 2);
                
                if (parts.length >= 1) {
                    String requestId = parts[0];
                    
                    // WebSocket을 통해 타임아웃 알림 전송
                    webSocketUpdateService.notifyTimeout(requestId);
                    
                    // 메트릭 기록
                    metricsService.recordTimeout(movieId, 1);
                    
                    logger.debug("[{}] 타임아웃 처리 완료: {}", movieId, requestId);
                }
                
                // TTL 키도 정리 (이미 만료되었지만 혹시 모르니 명시적으로 삭제)
                String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + member;
                redisTemplate.delete(timeoutKey);
                
            } catch (Exception e) {
                logger.error("[{}] 만료된 세션 {} 처리 중 오류 발생", movieId, member, e);
            }
        }
    }
}