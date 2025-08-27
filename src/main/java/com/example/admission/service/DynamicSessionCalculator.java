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

    @Value("${BASE_SESSIONS_PER_POD:2}")
    private int baseSessionsPerPod;

    @Value("${MAX_TOTAL_SESSIONS:1000}")
    private int maxTotalSessionsLimit;

    @Value("${FALLBACK_POD_COUNT:1}")
    private int fallbackPodCount;

    public DynamicSessionCalculator(PodDiscoveryService podDiscoveryService) {
        this.podDiscoveryService = podDiscoveryService;
    }

    /**
     * 현재 시스템이 수용 가능한 총 활성 세션 수를 계산합니다.
     */
    public long calculateMaxActiveSessions() {
        int currentPodCount = getPodCount();
        long calculatedSessions = (long) currentPodCount * baseSessionsPerPod;
        long finalMaxSessions = Math.min(calculatedSessions, maxTotalSessionsLimit);

        logger.debug("최대 활성 세션 수 계산: Pod {}개 * {}세션 = {} -> 최종 {} (최대 제한 {})",
                currentPodCount, baseSessionsPerPod, calculatedSessions, finalMaxSessions, maxTotalSessionsLimit);

        return finalMaxSessions;
    }

    private int getPodCount() {
        if (!dynamicScalingEnabled) {
            logger.warn("동적 스케일링 비활성화. Fallback Pod 수({})를 사용합니다.", fallbackPodCount);
            return fallbackPodCount;
        }

        try {
            int discoveredPods = podDiscoveryService.getPodCount();
            if (discoveredPods <= 0) {
                logger.error("Pod 수가 0 이하로 감지됨 ({}). Fallback Pod 수({})를 사용합니다.", discoveredPods, fallbackPodCount);
                return fallbackPodCount;
            }
            logger.info("성공적으로 Pod 수를 감지했습니다: {}개", discoveredPods);
            return discoveredPods;
        } catch (Exception e) {
            logger.error("Kubernetes API를 통해 Pod 수를 가져오는 데 실패했습니다. Fallback Pod 수({})를 사용합니다. 에러: {}",
                    fallbackPodCount, e.getMessage());
            return fallbackPodCount;
        }
    }

    public SessionCalculationInfo getCalculationInfo() {
        boolean k8sAvailable = podDiscoveryService.isKubernetesClientAvailable();
        int currentPodCount = k8sAvailable && dynamicScalingEnabled ? podDiscoveryService.getPodCount() : fallbackPodCount;
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
            int baseSessionsPerPod,
            int maxTotalSessionsLimit,
            int fallbackPodCount,
            boolean kubernetesAvailable,
            int currentPodCount,
            long calculatedMaxSessions
    ) {}
}