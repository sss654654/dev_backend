// src/main/java/com/example/admission/service/DynamicSessionCalculator.java
package com.example.admission.service;

import com.example.pod.service.PodDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DynamicSessionCalculator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSessionCalculator.class);

    private final PodDiscoveryService podDiscoveryService;

    @Value("${ENABLE_DYNAMIC_SCALING:true}")
    private boolean dynamicScalingEnabled;

    // ✅ 수정: 활성 세션 크기를 200으로 설정
    @Value("${BASE_SESSIONS_PER_POD:200}")
    private int baseSessionsPerPod;

    // ✅ 수정: 최대 총 세션을 1000으로 설정 (여유분 포함)
    @Value("${MAX_TOTAL_SESSIONS:1000}")
    private int maxTotalSessionsLimit;

    // ✅ 수정: Fallback Pod 수를 2개로 설정
    @Value("${FALLBACK_POD_COUNT:2}")
    private int fallbackPodCount;

    public DynamicSessionCalculator(PodDiscoveryService podDiscoveryService) {
        this.podDiscoveryService = podDiscoveryService;
    }

    /**
     * ✅ 핵심 로직: 2개 Pod × 200세션 = 400개 활성 세션
     * 500명 요청시 → 400명 즉시 입장, 100명 대기열
     */
    public long calculateMaxActiveSessions() {
        int currentPodCount = getPodCount();
        long calculatedSessions = (long) currentPodCount * baseSessionsPerPod;
        long finalMaxSessions = Math.min(calculatedSessions, maxTotalSessionsLimit);

        logger.info("📊 세션 계산 완료: Pod {}개 × {}세션 = {} (최대 제한: {})", 
                   currentPodCount, baseSessionsPerPod, calculatedSessions, maxTotalSessionsLimit);

        return finalMaxSessions;
    }

    private int getPodCount() {
        if (!dynamicScalingEnabled) {
            logger.info("⚙️ 동적 스케일링 비활성화. Fallback Pod 수({})를 사용합니다.", fallbackPodCount);
            return fallbackPodCount;
        }

        try {
            int discoveredPods = podDiscoveryService.getPodCount();
            if (discoveredPods <= 0) {
                logger.error("❌ Pod 수가 0 이하로 감지됨 ({}). Fallback Pod 수({})를 사용합니다.", 
                           discoveredPods, fallbackPodCount);
                return fallbackPodCount;
            }
            logger.info("✅ Kubernetes에서 Pod 수 확인: {}개", discoveredPods);
            return discoveredPods;
        } catch (Exception e) {
            logger.error("❌ Kubernetes API 호출 실패. Fallback Pod 수({})를 사용합니다. 에러: {}",
                        fallbackPodCount, e.getMessage());
            return fallbackPodCount;
        }
    }

    public SessionCalculationInfo getCalculationInfo() {
        boolean k8sAvailable = podDiscoveryService.isKubernetesClientAvailable();
        int currentPodCount = k8sAvailable && dynamicScalingEnabled ? 
            podDiscoveryService.getPodCount() : fallbackPodCount;
        long calculated = (long) currentPodCount * baseSessionsPerPod;
        long finalMax = Math.min(calculated, maxTotalSessionsLimit);

        return new SessionCalculationInfo(
                dynamicScalingEnabled,
                baseSessionsPerPod,
                maxTotalSessionsLimit,
                fallbackPodCount,
                k8sAvailable,
                currentPodCount,
                finalMax
        );
    }

    public record SessionCalculationInfo(
            boolean dynamicScalingEnabled,
            int baseSessionsPerPod,           // 200
            int maxTotalSessionsLimit,        // 1000
            int fallbackPodCount,             // 2
            boolean kubernetesAvailable,
            int currentPodCount,              // 2 (실제 또는 Fallback)
            long calculatedMaxSessions        // 400 (2×200)
    ) {
        
        /**
         * ✅ 대기 시간 계산 헬퍼 메서드
         * @param queuePosition 대기열에서의 순위 (1부터 시작)
         * @return 예상 대기 시간 (초)
         */
        public int calculateEstimatedWaitTimeSeconds(long queuePosition) {
            if (queuePosition <= 0) return 0;
            
            // Pod당 대기 시간 10초 가정
            int waitTimePerPodSeconds = 10;
            
            // 몇 개의 Pod 처리 후에 내 차례가 올지 계산
            long podsToWait = (queuePosition - 1) / baseSessionsPerPod;
            
            return (int)(podsToWait * waitTimePerPodSeconds);
        }
    }
}