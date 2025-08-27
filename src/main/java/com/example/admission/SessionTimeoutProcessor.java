// src/main/java/com/example/admission/SessionTimeoutProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);

    private final AdmissionService admissionService;
    private final WebSocketUpdateService webSocketUpdateService;
    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    public SessionTimeoutProcessor(AdmissionService admissionService,
                                   WebSocketUpdateService webSocketUpdateService,
                                   AdmissionMetricsService metricsService,
                                   LoadBalancingOptimizer loadBalancer) {
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    @Scheduled(fixedDelayString = "${sessionCleanupInterval:10000}")
    public void processExpiredSessions() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds == null || movieIds.isEmpty()) {
                return;
            }

            for (String movieId : movieIds) {
                if (loadBalancer.shouldProcessMovie(movieId)) {
                    processMovieExpiredSessions("movie", movieId);
                }
            }
        } catch (Exception e) {
            logger.error("만료 세션 정리 중 오류 발생", e);
        }
    }
    
    private void processMovieExpiredSessions(String type, String movieId) {
        try {
            Set<String> expiredMembers = admissionService.findExpiredActiveSessions(type, movieId);

            if (expiredMembers.isEmpty()) {
                return;
            }

            logger.warn("[{}] 타임아웃된 활성 세션 {}개를 정리합니다.", movieId, expiredMembers.size());
            admissionService.removeActiveSessions(type, movieId, expiredMembers);

            for (String member : expiredMembers) {
                String requestId = member.split(":")[0];
                webSocketUpdateService.notifyTimeout(requestId);
                metricsService.recordTimeout(movieId, 1);
            }
        } catch (Exception e) {
            logger.error("[{}] 만료 세션 처리 중 오류", movieId, e);
        }
    }
}