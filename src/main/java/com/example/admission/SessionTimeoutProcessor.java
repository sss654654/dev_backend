// src/main/java/com/example/admission/SessionTimeoutProcessor.java

package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.AdmissionService; // AdmissionService import
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

    private final AdmissionService admissionService; // AdmissionService 주입
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

    @Scheduled(fixedDelayString = "${admission.session-processor-interval-ms:2000}")
    public void processExpiredSessions() {
        try {
            // ✅ 이제 대기열이 있는 영화가 아닌, 활성 세션이 있는 모든 영화를 대상으로 해야 합니다.
            // AdmissionService에 활성 세션이 있는 영화 목록을 가져오는 메소드가 필요하지만,
            // 우선은 기존 로직을 활용하여 대기열이 있는 영화를 대상으로 처리합니다.
            // (추후 active_movies_zset:* 패턴으로 스캔하여 개선 가능)
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds == null || movieIds.isEmpty()) {
                return;
            }

            logger.debug("만료된 세션 정리 시작 - {} 개 영화 처리", movieIds.size());

            for (String movieId : movieIds) {
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    continue;
                }
                processMovieExpiredSessions("movie", movieId);
            }

        } catch (Exception e) {
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
    }

    /**
     * ✅ [전체 수정] 능동적으로 타임아웃된 세션을 찾아 처리하는 로직
     */
    private void processMovieExpiredSessions(String type, String movieId) {
        try {
            // 1. 만료 시간이 지난 세션들을 찾는다.
            Set<String> expiredMembers = admissionService.findExpiredActiveSessions(type, movieId);

            if (expiredMembers.isEmpty()) {
                return;
            }

            logger.warn("[{}] 타임아웃된 활성 세션 {}개를 발견하여 정리합니다.", movieId, expiredMembers.size());

            // 2. 만료된 세션들을 DB에서 일괄 삭제한다.
            admissionService.removeActiveSessions(type, movieId, expiredMembers);

            // 3. 만료된 사용자들에게 알림을 보낸다.
            for (String member : expiredMembers) {
                String requestId = member.split(":")[0];
                webSocketUpdateService.notifyTimeout(requestId);
                metricsService.recordTimeout(movieId, 1);
            }

            logger.info("[{}] 타임아웃된 활성 세션 {}개 정리 완료", movieId, expiredMembers.size());

        } catch (Exception e) {
            logger.error("[{}] 만료된 세션 처리 중 오류", movieId, e);
        }
    }
}