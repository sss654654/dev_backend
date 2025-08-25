// src/main/java/com/example/pod/service/PodDiscoveryService.java

package com.example.pod.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.ClientBuilder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class PodDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(PodDiscoveryService.class);

    @Value("${kubernetes.namespace:default}")
    private String namespace;

    @Value("${kubernetes.app-label:app=cgv-api}")
    private String appLabel;

    private CoreV1Api coreV1Api;
    private boolean isKubernetesClientAvailable = false;

    @PostConstruct
    public void init() {
        try {
            ApiClient client = ClientBuilder.standard().build();
            Configuration.setDefaultApiClient(client);
            this.coreV1Api = new CoreV1Api();
            this.isKubernetesClientAvailable = true;
            logger.info("✅ Kubernetes 클라이언트가 성공적으로 초기화되었습니다. (Namespace: {}, App Label: {})", namespace, appLabel);
        } catch (IOException e) {
            logger.warn("🚨 Kubernetes 클라이언트를 초기화할 수 없습니다. Fallback 모드로 동작합니다. 에러: {}", e.getMessage());
            this.isKubernetesClientAvailable = false;
        }
    }

    public int getPodCount() {
        if (!isKubernetesClientAvailable) {
            logger.debug("K8s 클라이언트가 없어 Pod 수 조회를 건너뜁니다. 0을 반환합니다.");
            return 0;
        }

        try {
            // [핵심 수정] 빌더 패턴을 사용하여 요청을 조립하고, 마지막에 .execute()로 실행합니다.
            // 1. listNamespacedPod(namespace)로 요청 객체 생성
            // 2. .labelSelector(appLabel)로 레이블 필터 조건 추가
            // 3. .execute()로 API 요청을 실행하고 V1PodList 결과를 받음
            V1PodList list = coreV1Api.listNamespacedPod(namespace)
                                      .labelSelector(appLabel)
                                      .execute();

            int podCount = list.getItems().size();
            // 이제 Java에서 수동으로 필터링할 필요 없이,n K.ubernetes API가 직접 필터링해주므로 훨씬 효율적입니다.
            logger.info("Pod 수 조회 성공: '{}' 레이블을 가진 Pod {}개를 찾았습니다.", appLabel, podCount);
            return podCount;

        } catch (ApiException e) {
            logger.error("Kubernetes API 호출 중 오류가 발생했습니다. Pod 수를 0으로 간주합니다. (HTTP Status: {}, Body: {})", e.getCode(), e.getResponseBody());
            return 0;
        }
    }

    public boolean isKubernetesClientAvailable() {
        return this.isKubernetesClientAvailable;
    }
}