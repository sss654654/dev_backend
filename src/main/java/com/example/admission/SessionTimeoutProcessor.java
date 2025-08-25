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
    private void processMovieExpiredSessions(String movieId) {
        try {
            String activeSessionsKey = "active_sessions:movie:" + movieId;
            
            // active_sessions는 Set 타입이므로 opsForSet() 사용
            Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
            
            if (members == null || members.isEmpty()) {
                return;
            }

            // 만료된 세션들을 찾기 위한 로직
            List<String> expiredMembers = new ArrayList<>();
            
            for (String member : members) {
                String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + member;
                
                // TTL 키가 존재하지 않으면 만료된 것으로 간주
                if (Boolean.FALSE.equals(redisTemplate.hasKey(timeoutKey))) {
                    expiredMembers.add(member);
                }
            }

            if (!expiredMembers.isEmpty()) {
                logger.warn("[{}] 타임아웃된 활성 세션 {}개를 발견하여 정리합니다.", movieId, expiredMembers.size());

                // active_sessions Set에서 만료된 멤버들 일괄 삭제
                redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());

                // 각 만료된 세션에 대해 후속 처리
                processExpiredMembers(movieId, expiredMembers);
                
                logger.info("[{}] 타임아웃된 활성 세션 {}개 정리 완료", movieId, expiredMembers.size());
            }
            
        } catch (Exception e) {
            logger.error("[{}] 영화 세션 정리 중 오류 발생", movieId, e);
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