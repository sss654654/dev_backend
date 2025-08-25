// com/example/admission/SessionTimeoutProcessor.java
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Scheduled(fixedDelayString = "${admission.session-processor-interval-ms:5000}") // 주기를 5초로 늘려 안정성 확보
    public void processExpiredSessions() {
        Set<String> movieIds = redisTemplate.opsForSet().members("active_movies");
        if (movieIds == null || movieIds.isEmpty()) return;

        for (String movieId : movieIds) {
            if (!loadBalancer.shouldProcessMovie(movieId)) continue;

            String activeSessionsKey = "active_sessions:movie:" + movieId;
            Set<String> members = redisTemplate.opsForSet().members(activeSessionsKey);
            if (members == null || members.isEmpty()) continue;

            // [핵심 수정] 만료된 세션을 찾기 위한 새로운 로직
            List<String> expiredMembers = new ArrayList<>();
            for (String member : members) {
                String timeoutKey = "active_user_ttl:movie:" + movieId + ":" + member;
                // TTL 키가 존재하는지 확인. 존재하지 않으면 만료된 것으로 간주.
                if (Boolean.FALSE.equals(redisTemplate.hasKey(timeoutKey))) {
                    expiredMembers.add(member);
                }
            }

            if (!expiredMembers.isEmpty()) {
                logger.warn("[{}] 타임아웃된 활성 세션 {}개를 발견하여 정리합니다.", movieId, expiredMembers.size());

                // active_sessions Set에서 만료된 멤버들 일괄 삭제
                redisTemplate.opsForSet().remove(activeSessionsKey, expiredMembers.toArray());

                expiredMembers.forEach(member -> {
                    // member 형식: "requestId:sessionId"
                    String requestId = member.split(":", 2)[0];
                    webSocketUpdateService.notifyTimeout(requestId);
                    metricsService.recordTimeout(movieId, 1);
                });
            }
        }
    }
}