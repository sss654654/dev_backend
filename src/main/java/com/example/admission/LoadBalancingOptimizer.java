package com.example.admission.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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

    public boolean shouldProcessMovie(String movieId) {
        if (!enableLoadBalancing) {
            return true;
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

    private boolean shouldProcessRoundRobin(String movieId) {
        List<String> activePods = getActivePods();
        if (activePods.isEmpty() || !activePods.contains(podId)) {
             // Pod 목록이 비었거나 내가 아직 목록에 없다면, 잠시 후 다시 시도하도록 false 반환
             logger.warn("활성 Pod 목록에 현재 Pod({})이 없습니다. 목록: {}", podId, activePods);
             return false;
        }
        
        int movieHash = Math.abs(movieId.hashCode());
        int assignedPodIndex = movieHash % activePods.size();
        String assignedPod = activePods.get(assignedPodIndex);
        
        boolean shouldProcess = podId.equals(assignedPod);
        
        if (shouldProcess) {
            logger.debug("Round Robin - 영화 {} 처리 담당: {}", movieId, podId);
        }
        
        return shouldProcess;
    }

    private boolean shouldProcessHashBased(String movieId) {
        List<String> activePods = getActivePods();
         if (activePods.isEmpty() || !activePods.contains(podId)) {
             return false;
        }
        
        int targetHash = Math.abs(movieId.hashCode());
        String assignedPod = activePods.stream()
            .min(Comparator.comparingInt(pod -> 
                Math.abs(pod.hashCode() - targetHash)))
            .orElse(podId);
        
        return podId.equals(assignedPod);
    }

    private boolean shouldProcessLeastLoaded(String movieId) {
        try {
            int myLoad = getCurrentPodLoad();
            
            List<String> activePods = getActivePods();
            if (activePods.isEmpty() || !activePods.contains(podId)) {
                return false;
            }

            for (String otherPod : activePods) {
                if (!otherPod.equals(podId)) {
                    int otherLoad = getPodLoad(otherPod);
                    if (otherLoad < myLoad) {
                        return false;
                    }
                }
            }
            
            logger.debug("Least Loaded - 영화 {} 처리 (현재 부하: {})", movieId, myLoad);
            return true;
            
        } catch (Exception e) {
            logger.error("최소 부하 계산 중 오류", e);
            return shouldProcessRoundRobin(movieId);
        }
    }

    // ★★★ 핵심 수정: Race Condition을 유발하던 로직을 제거하고, Redis의 데이터를 직접 신뢰하도록 변경
    private List<String> getActivePods() {
        try {
            String key = "load_balancer:active_pods";
            
            long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, fiveMinutesAgo);
            
            Set<String> pods = redisTemplate.opsForZSet().range(key, 0, -1);
            if (pods == null) {
                return Collections.emptyList();
            }
            // 모든 Pod가 동일한 순서의 리스트를 받도록 정렬
            return pods.stream().sorted().collect(Collectors.toList());
            
        } catch (Exception e) {
            logger.error("활성 Pod 목록 조회 실패", e);
            return List.of(podId); // Fallback
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
            return Integer.MAX_VALUE;
        }
    }

    public void updatePodLoad(int currentLoad) {
        try {
            String loadKey = "load_balancer:pod_load:" + podId;
            redisTemplate.opsForValue().set(loadKey, String.valueOf(currentLoad));
            redisTemplate.expire(loadKey, java.time.Duration.ofMinutes(10));
            
            registerPod();
            
        } catch (Exception e) {
            logger.error("Pod 부하 업데이트 실패", e);
        }
    }

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