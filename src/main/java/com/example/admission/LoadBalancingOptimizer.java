package com.example.admission.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class LoadBalancingOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(LoadBalancingOptimizer.class);
    
    private final StringRedisTemplate redisTemplate;
    private final String podId;
    
    @Value("${admission.enable-load-balancing:true}")
    private boolean enableLoadBalancing;
    
    @Value("${admission.load-balancing-strategy:ROUND_ROBIN}")
    private String loadBalancingStrategy;

    public LoadBalancingOptimizer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.podId = generatePodId();
        
        // Pod 등록
        registerPod();
    }

    private String generatePodId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
            return hostname + "-" + timestamp;
        } catch (Exception e) {
            logger.warn("호스트명 가져오기 실패, 랜덤 ID 생성", e);
            return "pod-" + ThreadLocalRandom.current().nextInt(1000, 9999);
        }
    }

    /**
     * Redis에 현재 Pod 등록
     */
    private void registerPod() {
        try {
            String key = "load_balancer:active_pods";
            redisTemplate.opsForZSet().add(key, podId, System.currentTimeMillis());
            redisTemplate.expire(key, java.time.Duration.ofMinutes(5));
            
            logger.info("Load Balancer에 Pod 등록: {}", podId);
        } catch (Exception e) {
            logger.error("Pod 등록 실패", e);
        }
    }

    /**
     * 특정 영화에 대한 처리 담당 Pod 결정
     */
    public boolean shouldProcessMovie(String movieId) {
        if (!enableLoadBalancing) {
            return true; // 부하 분산 비활성화 시 모든 Pod가 처리
        }

        try {
            return switch (loadBalancingStrategy.toUpperCase()) {
                case "ROUND_ROBIN" -> shouldProcessRoundRobin(movieId);
                case "HASH_BASED" -> shouldProcessHashBased(movieId);
                case "LEAST_LOADED" -> shouldProcessLeastLoaded(movieId);
                default -> true;
            };
        } catch (Exception e) {
            logger.error("부하 분산 결정 중 오류, 기본 처리로 진행", e);
            return true;
        }
    }

    /**
     * Round Robin 방식: 영화 ID를 순환하며 Pod에 할당
     */
    private boolean shouldProcessRoundRobin(String movieId) {
        List<String> activePods = getActivePods();
        if (activePods.isEmpty()) return true;
        
        int movieHash = Math.abs(movieId.hashCode());
        int assignedPodIndex = movieHash % activePods.size();
        String assignedPod = activePods.get(assignedPodIndex);
        
        boolean shouldProcess = podId.equals(assignedPod);
        
        if (shouldProcess) {
            logger.debug("Round Robin - 영화 {} 처리 담당: {}", movieId, podId);
        }
        
        return shouldProcess;
    }

    /**
     * Hash 기반 방식: 영화 ID 해시를 통한 일관된 할당
     */
    private boolean shouldProcessHashBased(String movieId) {
        List<String> activePods = getActivePods();
        if (activePods.isEmpty()) return true;
        
        // Consistent Hashing 구현
        int targetHash = Math.abs(movieId.hashCode());
        String assignedPod = activePods.stream()
            .min(Comparator.comparingInt(pod -> 
                Math.abs(pod.hashCode() - targetHash)))
            .orElse(podId);
        
        return podId.equals(assignedPod);
    }

    /**
     * 최소 부하 방식: 현재 처리량이 가장 적은 Pod가 처리
     */
    private boolean shouldProcessLeastLoaded(String movieId) {
        try {
            // 현재 Pod의 작업 부하 확인
            int myLoad = getCurrentPodLoad();
            
            // 다른 Pod들의 부하와 비교
            List<String> activePods = getActivePods();
            for (String otherPod : activePods) {
                if (!otherPod.equals(podId)) {
                    int otherLoad = getPodLoad(otherPod);
                    if (otherLoad < myLoad) {
                        return false; // 다른 Pod가 더 적게 부하 받고 있음
                    }
                }
            }
            
            logger.debug("Least Loaded - 영화 {} 처리 (현재 부하: {})", movieId, myLoad);
            return true;
            
        } catch (Exception e) {
            logger.error("최소 부하 계산 중 오류", e);
            return shouldProcessRoundRobin(movieId); // Fallback
        }
    }

    private List<String> getActivePods() {
        try {
            String key = "load_balancer:active_pods";
            
            // 5분 이상 오래된 Pod 정리
            long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, fiveMinutesAgo);
            
            Set<String> pods = redisTemplate.opsForZSet().range(key, 0, -1);
            List<String> result = pods != null ? new ArrayList<>(pods) : new ArrayList<>();
            
            // 현재 Pod이 목록에 없으면 추가
            if (!result.contains(podId)) {
                registerPod();
                result.add(podId);
            }
            
            return result.stream().sorted().toList(); // 일관된 순서
            
        } catch (Exception e) {
            logger.error("활성 Pod 목록 조회 실패", e);
            return List.of(podId); // Fallback: 자신만 반환
        }
    }

    private int getCurrentPodLoad() {
        try {
            String loadKey = "load_balancer:pod_load:" + podId;
            String loadStr = redisTemplate.opsForValue().get(loadKey);
            return loadStr != null ? Integer.parseInt(loadStr) : 0;
        } catch (Exception e) {
            logger.error("Pod 부하 조회 실패", e);
            return 0;
        }
    }

    private int getPodLoad(String otherPodId) {
        try {
            String loadKey = "load_balancer:pod_load:" + otherPodId;
            String loadStr = redisTemplate.opsForValue().get(loadKey);
            return loadStr != null ? Integer.parseInt(loadStr) : 0;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // 조회 실패 시 높은 부하로 가정
        }
    }

    /**
     * 현재 Pod의 작업 부하 업데이트
     */
    public void updatePodLoad(int currentLoad) {
        try {
            String loadKey = "load_balancer:pod_load:" + podId;
            redisTemplate.opsForValue().set(loadKey, String.valueOf(currentLoad));
            redisTemplate.expire(loadKey, java.time.Duration.ofMinutes(10));
            
            // 주기적으로 Pod 등록 갱신
            registerPod();
            
        } catch (Exception e) {
            logger.error("Pod 부하 업데이트 실패", e);
        }
    }

    /**
     * 부하 분산 상태 정보 반환
     */
    public Map<String, Object> getLoadBalancingStatus() {
        Map<String, Object> status = new HashMap<>();
        
        try {
            List<String> activePods = getActivePods();
            int myLoad = getCurrentPodLoad();
            
            status.put("podId", podId);
            status.put("activePods", activePods);
            status.put("totalActivePods", activePods.size());
            status.put("currentLoad", myLoad);
            status.put("strategy", loadBalancingStrategy);
            status.put("enabled", enableLoadBalancing);
            
        } catch (Exception e) {
            logger.error("부하 분산 상태 조회 실패", e);
            status.put("error", e.getMessage());
        }
        
        return status;
    }
}