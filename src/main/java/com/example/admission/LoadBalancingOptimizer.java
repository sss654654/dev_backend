// src/main/java/com/example/admission/service/LoadBalancingOptimizer.java - Pod 목록 관리 개선

package com.example.admission.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
        logger.info("🎯 LoadBalancingOptimizer 초기화 - podId: {}, 전략: {}, 활성화: {}", 
                   podId, loadBalancingStrategy, enableLoadBalancing);
    }

    @PostConstruct
    public void initialize() {
        registerPod();
        // 초기화 시 한 번 정리 실행
        cleanupExpiredPods();
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
     * 🔄 [정기 실행] Pod를 Redis에 등록하고 생존 신호를 보냅니다
     */
    @Scheduled(fixedDelay = 30000) // 30초마다 실행
    public void registerPod() {
        try {
            String key = "load_balancer:active_pods";
            long currentTime = System.currentTimeMillis();
            
            // 현재 Pod를 활성 목록에 추가 (score는 현재 시간)
            redisTemplate.opsForZSet().add(key, podId, currentTime);
            
            // TTL 설정 (5분)
            redisTemplate.expire(key, java.time.Duration.ofMinutes(5));
            
            logger.debug("🔄 Pod 생존 신호 전송 완료: {} (시간: {})", podId, currentTime);
            
        } catch (Exception e) {
            logger.error("❌ Pod 등록 실패", e);
        }
    }

    /**
     * 🧹 [정기 실행] 만료된 Pod들을 정리합니다
     */
    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    public void cleanupExpiredPods() {
        try {
            String key = "load_balancer:active_pods";
            long threeMinutesAgo = System.currentTimeMillis() - (3 * 60 * 1000);
            
            // 3분 이상 신호가 없는 Pod들 제거
            Long removedCount = redisTemplate.opsForZSet().removeRangeByScore(key, 0, threeMinutesAgo);
            
            if (removedCount != null && removedCount > 0) {
                logger.info("🧹 만료된 Pod {}개 정리 완료", removedCount);
            }
            
        } catch (Exception e) {
            logger.error("❌ 만료 Pod 정리 실패", e);
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
                default -> {
                    logger.warn("⚠️ 알 수 없는 부하분산 전략: {}, 기본 처리로 진행", loadBalancingStrategy);
                    yield true;
                }
            };
        } catch (Exception e) {
            logger.error("❌ 부하 분산 결정 중 오류, 기본 처리로 진행", e);
            return true;
        }
    }

    private boolean shouldProcessRoundRobin(String movieId) {
        List<String> activePods = getActivePods();
        
        if (activePods.isEmpty()) {
            logger.warn("⚠️ 활성 Pod 목록이 비어있습니다. 현재 Pod({})만 처리합니다.", podId);
            return true; // 활성 Pod가 없으면 현재 Pod가 처리
        }
        
        if (!activePods.contains(podId)) {
            logger.warn("⚠️ 활성 Pod 목록에 현재 Pod({})이 없습니다. 목록: {} | 강제 등록 후 처리합니다.", 
                       podId, activePods);
            registerPod(); // 강제로 다시 등록
            return true; // 등록 후 처리
        }
        
        int movieHash = Math.abs(movieId.hashCode());
        int assignedPodIndex = movieHash % activePods.size();
        String assignedPod = activePods.get(assignedPodIndex);
        
        boolean shouldProcess = podId.equals(assignedPod);
        if (shouldProcess) {
            logger.debug("🎯 Round Robin - 영화 {} 처리 담당: {} (인덱스: {}/{})", 
                        movieId, podId, assignedPodIndex, activePods.size());
        } else {
            logger.debug("⏸️ Round Robin - 영화 {} 처리 담당 아님: 담당={}, 현재={}", 
                        movieId, assignedPod, podId);
        }
        return shouldProcess;
    }

    private boolean shouldProcessHashBased(String movieId) {
        List<String> activePods = getActivePods();
        if (activePods.isEmpty() || !activePods.contains(podId)) {
            logger.warn("⚠️ Hash-Based: Pod 목록 문제, 기본 처리 적용");
            return true;
        }
        
        int targetHash = Math.abs(movieId.hashCode());
        String assignedPod = activePods.stream()
            .min(Comparator.comparingInt(pod -> Math.abs(pod.hashCode() - targetHash)))
            .orElse(podId);
        
        boolean shouldProcess = podId.equals(assignedPod);
        if (shouldProcess) {
            logger.debug("🎯 Hash-Based - 영화 {} 처리 담당: {}", movieId, podId);
        }
        return shouldProcess;
    }

    private boolean shouldProcessLeastLoaded(String movieId) {
        try {
            int myLoad = getCurrentPodLoad();
            List<String> activePods = getActivePods();
            if (activePods.isEmpty() || !activePods.contains(podId)) {
                logger.warn("⚠️ Least-Loaded: Pod 목록 문제, 기본 처리 적용");
                return true;
            }
            
            for (String otherPod : activePods) {
                if (!otherPod.equals(podId)) {
                    int otherLoad = getPodLoad(otherPod);
                    if (otherLoad < myLoad) {
                        logger.debug("⏸️ Least-Loaded - 다른 Pod의 부하가 더 낮음: 현재={}({}), 다른={}({})", 
                                    podId, myLoad, otherPod, otherLoad);
                        return false;
                    }
                }
            }
            logger.debug("🎯 Least-Loaded - 영화 {} 처리 (현재 부하: {})", movieId, myLoad);
            return true;
        } catch (Exception e) {
            logger.error("❌ 최소 부하 계산 중 오류", e);
            return shouldProcessRoundRobin(movieId);
        }
    }

    private List<String> getActivePods() {
        try {
            String key = "load_balancer:active_pods";
            
            // 먼저 만료된 Pod 정리
            long fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000);
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, fiveMinutesAgo);
            
            // 활성 Pod 목록 조회
            Set<String> pods = redisTemplate.opsForZSet().range(key, 0, -1);
            if (pods == null) {
                return Collections.emptyList();
            }
            
            List<String> sortedPods = pods.stream().sorted().collect(Collectors.toList());
            logger.debug("📋 활성 Pod 목록 조회: {} (총 {}개)", sortedPods, sortedPods.size());
            return sortedPods;
            
        } catch (Exception e) {
            logger.error("❌ 활성 Pod 목록 조회 실패", e);
            return List.of(podId); // 실패 시 현재 Pod만 반환
        }
    }

    private int getCurrentPodLoad() {
        try {
            String loadKey = "load_balancer:pod_load:" + podId;
            String loadStr = redisTemplate.opsForValue().get(loadKey);
            return loadStr != null ? Integer.parseInt(loadStr) : 0;
        } catch (Exception e) {
            logger.error("❌ Pod 부하 조회 실패", e);
            return 0;
        }
    }

    private int getPodLoad(String otherPodId) {
        try {
            String loadKey = "load_balancer:pod_load:" + otherPodId;
            String loadStr = redisTemplate.opsForValue().get(loadKey);
            return loadStr != null ? Integer.parseInt(loadStr) : 0;
        } catch (Exception e) {
            return Integer.MAX_VALUE; // 조회 실패 시 최대값 반환 (낮은 우선순위)
        }
    }

    /**
     * 🔄 Pod 부하 정보 업데이트
     */
    public void updatePodLoad(int currentLoad) {
        try {
            String loadKey = "load_balancer:pod_load:" + podId;
            redisTemplate.opsForValue().set(loadKey, String.valueOf(currentLoad), java.time.Duration.ofMinutes(10));
            
            // Pod 등록도 함께 갱신
            registerPod();
            
            logger.debug("📊 Pod 부하 정보 업데이트: {} -> {}", podId, currentLoad);
            
        } catch (Exception e) {
            logger.error("❌ Pod 부하 업데이트 실패", e);
        }
    }

    /**
     * 📊 부하 분산 상태 정보 조회
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
            status.put("isInActiveList", activePods.contains(podId));
            
        } catch (Exception e) {
            logger.error("❌ 부하 분산 상태 조회 실패", e);
            status.put("error", e.getMessage());
        }
        return status;
    }

    /**
     * 🔧 현재 Pod ID 조회
     */
    public String getPodId() {
        return podId;
    }
}