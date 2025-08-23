package com.example.admission.service;

import com.example.k8s.service.PodDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DynamicSessionCalculator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSessionCalculator.class);

    @Value("${admission.base-sessions-per-pod:2}")
    private int baseSessionsPerPod;

    @Value("${admission.max-total-sessions:50}")
    private int maxTotalSessions;

    @Value("${admission.enable-dynamic-scaling:true}")
    private boolean enableDynamicScaling;

    private final PodDiscoveryService podDiscoveryService;

    public DynamicSessionCalculator(PodDiscoveryService podDiscoveryService) {
        this.podDiscoveryService = podDiscoveryService;
    }

    /**
     * 현재 Pod 수에 따라 동적으로 최대 활성 세션 수를 계산
     * 
     * @return 계산된 최대 활성 세션 수
     */
    public long calculateMaxActiveSessions() {
        if (!enableDynamicScaling) {
            logger.debug("동적 스케일링이 비활성화되어 있습니다. 기본값({})을 사용합니다.", baseSessionsPerPod);
            return baseSessionsPerPod;
        }

        int currentPodCount = podDiscoveryService.getCurrentPodCount();
        long calculatedSessions = (long) currentPodCount * baseSessionsPerPod;
        
        // 최대 제한값 적용
        long finalSessions = Math.min(calculatedSessions, maxTotalSessions);

        logger.debug("Pod 수: {}, Pod당 기본 세션: {}, 계산된 세션: {}, 최종 세션: {}", 
            currentPodCount, baseSessionsPerPod, calculatedSessions, finalSessions);

        return finalSessions;
    }

    /**
     * 현재 설정 정보를 반환 (디버깅/모니터링용)
     */
    public SessionCalculationInfo getCalculationInfo() {
        int currentPodCount = podDiscoveryService.getCurrentPodCount();
        long maxSessions = calculateMaxActiveSessions();
        
        return new SessionCalculationInfo(
            currentPodCount,
            baseSessionsPerPod,
            maxSessions,
            maxTotalSessions,
            enableDynamicScaling,
            podDiscoveryService.isKubernetesAvailable()
        );
    }

    /**
     * 세션 계산 정보를 담는 내부 클래스
     */
    public record SessionCalculationInfo(
        int currentPodCount,
        int baseSessionsPerPod,
        long calculatedMaxSessions,
        int maxTotalSessionsLimit,
        boolean dynamicScalingEnabled,
        boolean kubernetesAvailable
    ) {}
}