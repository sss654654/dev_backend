// com/example/admission/service/DynamicSessionCalculator.java

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

    // application.yml 또는 환경변수에서 설정값을 가져옵니다.
    @Value("${admission.dynamic-scaling.enabled:true}")
    private boolean dynamicScalingEnabled;

    @Value("${admission.base-sessions-per-pod:2}")
    private int baseSessionsPerPod;

    @Value("${admission.max-total-sessions-limit:1000}")
    private int maxTotalSessionsLimit;

    // [핵심 수정] K8s 연동 실패 시 사용할 안전한 대체 Pod 수
    @Value("${admission.fallback-pod-count:1}")
    private int fallbackPodCount;

    public DynamicSessionCalculator(PodDiscoveryService podDiscoveryService) {
        this.podDiscoveryService = podDiscoveryService;
    }

    /**
     * 현재 시스템이 수용 가능한 총 활성 세션 수를 계산합니다.
     * @return 계산된 최대 활성 세션 수
     */
    public long calculateMaxActiveSessions() {
        int currentPodCount = getPodCount();
        long calculatedSessions = (long) currentPodCount * baseSessionsPerPod;

        // 최종 세션 수는 설정된 최대 제한값을 넘을 수 없습니다.
        long finalMaxSessions = Math.min(calculatedSessions, maxTotalSessionsLimit);

        logger.debug("최대 활성 세션 수 계산 완료: Pod {}개 * Pod당 {}세션 = {} -> 최종 {} (최대 제한 {})",
                currentPodCount, baseSessionsPerPod, calculatedSessions, finalMaxSessions, maxTotalSessionsLimit);

        return finalMaxSessions;
    }

    /**
     * 현재 Pod 수를 가져옵니다. 동적 스케일링이 비활성화되었거나 K8s 연동에 실패하면
     * 안전한 fallback 값을 사용합니다.
     */
    private int getPodCount() {
        if (!dynamicScalingEnabled) {
            logger.warn("동적 스케일링 비활성화 상태. Fallback Pod 수({})를 사용합니다.", fallbackPodCount);
            return fallbackPodCount;
        }

        try {
            int discoveredPods = podDiscoveryService.getPodCount();
            // Pod 수가 0으로 감지되는 비정상적인 경우를 방지
            if (discoveredPods <= 0) {
                logger.error("Pod 수가 0 이하로 감지되었습니다 ({}). Fallback Pod 수({})를 사용합니다.", discoveredPods, fallbackPodCount);
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

    /**
     * 현재 설정을 외부 모니터링 API에서 조회할 수 있도록 제공하는 메소드입니다.
     */
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

    // DTO Record for exposing configuration
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