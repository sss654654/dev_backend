package com.example.k8s.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.Config;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PodDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(PodDiscoveryService.class);

    @Value("${spring.application.name:user-management-backend}")
    private String appName;

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    @Value("${admission.fallback-pod-count:1}")
    private int fallbackPodCount;

    @Value("${admission.enable-k8s-discovery:true}")
    private boolean enableK8sDiscovery;

    private CoreV1Api coreV1Api;
    private final AtomicInteger currentPodCount = new AtomicInteger(1);
    private boolean kubernetesAvailable = false;

    @PostConstruct
    public void init() {
        if (!enableK8sDiscovery) {
            logger.info("Kubernetes Pod Discovery가 비활성화되어 있습니다. fallback 값({})을 사용합니다.", fallbackPodCount);
            currentPodCount.set(fallbackPodCount);
            return;
        }

        try {
            // Kubernetes API 클라이언트 초기화
            ApiClient client = Config.defaultClient();
            io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
            this.coreV1Api = new CoreV1Api();
            this.kubernetesAvailable = true;
            
            logger.info("Kubernetes API 클라이언트 초기화 성공");
            
            // 초기 Pod 수 조회
            updatePodCount();
            
        } catch (IOException e) {
            logger.warn("Kubernetes API 클라이언트 초기화 실패 (로컬 개발환경일 가능성): {}", e.getMessage());
            logger.info("fallback Pod 수({})를 사용합니다.", fallbackPodCount);
            this.kubernetesAvailable = false;
            currentPodCount.set(fallbackPodCount);
        }
    }

    /**
     * 10초마다 Pod 수를 업데이트
     */
    @Scheduled(fixedRate = 10000)
    public void updatePodCount() {
        if (!kubernetesAvailable || !enableK8sDiscovery) {
            return;
        }

        try {
            String labelSelector = "app=" + appName;
            V1PodList podList = coreV1Api.listNamespacedPod(
                namespace,
                null, // pretty
                null, // allowWatchBookmarks  
                null, // _continue
                null, // fieldSelector
                labelSelector, // labelSelector - 우리 앱의 Pod들만 조회
                null, // limit
                null, // resourceVersion
                null, // resourceVersionMatch
                null, // sendInitialEvents
                null, // timeoutSeconds
                null  // watch
            );

            if (podList.getItems() != null) {
                // Running 상태인 Pod들만 카운트
                List<V1Pod> runningPods = podList.getItems().stream()
                    .filter(pod -> pod.getStatus() != null && 
                                  "Running".equals(pod.getStatus().getPhase()))
                    .toList();

                int newPodCount = runningPods.size();
                int previousCount = currentPodCount.get();
                
                if (newPodCount != previousCount) {
                    currentPodCount.set(newPodCount);
                    logger.info("Pod 수가 업데이트되었습니다: {} -> {} (labelSelector: {})", 
                        previousCount, newPodCount, labelSelector);
                } else {
                    logger.debug("현재 실행 중인 Pod 수: {}", newPodCount);
                }
            }

        } catch (ApiException e) {
            logger.error("Pod 목록 조회 실패: {} - {}", e.getCode(), e.getResponseBody());
            // API 호출 실패 시 기존 값 유지
        } catch (Exception e) {
            logger.error("Pod 수 업데이트 중 예외 발생", e);
        }
    }

    /**
     * 현재 실행 중인 Pod 수를 반환
     */
    public int getCurrentPodCount() {
        return currentPodCount.get();
    }

    /**
     * Kubernetes 환경 사용 가능 여부
     */
    public boolean isKubernetesAvailable() {
        return kubernetesAvailable;
    }
}