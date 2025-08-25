// com/example/admission/SessionTimeoutProcessor.java

package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Scheduled(fixedDelayString = "${admission.session-processor-interval-ms:5000}")
    public void processExpiredSessions() {
        try {
            // [수정] 'active_movies' 인덱스를 사용하여 활성 세션이 있는 영화만 조회
            Set<String> movieIds = redisTemplate.opsForSet().members("active_movies");
            if (movieIds == null || movieIds.isEmpty()) return;

            for (String movieId : movieIds) {
                if (!loadBalancer.shouldProcessMovie(movieId)) continue;

                String activeSessionsKey = "active_sessions:movie:" + movieId;
                // [수정] 'active_sessions'는 Set 타입이므로 Set 명령어로 멤버를 가져옵니다.
                Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
                if (members == null || members.isEmpty()) {
                    // 활성 세션이 비었으면 인덱스에서 제거
                    redisTemplate.opsForSet().remove("active_movies", movieId);
                    continue;
                }

                List<String> expiredMembers = new ArrayList<>();
                for (String member : members) {
                    // 각 멤버에 대해 TTL 키가 존재하는지 확인합니다.
                    String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + member;
                    // TTL 키가 없으면 만료된 것으로 간주합니다.
                    if (Boolean.FALSE.equals(redisTemplate.hasKey(timeoutKey))) {
                        expiredMembers.add(member);
                    }
                }

                if (!expiredMembers.isEmpty()) {
                    logger.warn("[{}] 타임아웃된 활성 세션 {}개를 발견하여 정리합니다.", movieId, expiredMembers.size());
                    
                    // Set에서 만료된 멤버들을 한번에 제거합니다.
                    redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());

                    expiredMembers.forEach(member -> {
                        String requestId = member.split(":", 2)[0];
                        webSocketUpdateService.notifyTimeout(requestId);
                        metricsService.recordTimeout(movieId, 1);
                    });
                }
            }
        } catch (Exception e) {
            // [수정] 오류 발생 시 더 명확한 로그를 남깁니다.
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
    }
}