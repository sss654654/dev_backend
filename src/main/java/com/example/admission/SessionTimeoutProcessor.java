package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.nio.charset.StandardCharsets;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    
    private final StringRedisTemplate redisTemplate;
    private final WebSocketUpdateService webSocketUpdateService;
    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    public SessionTimeoutProcessor(StringRedisTemplate redisTemplate, 
                                     AdmissionService admissionService, 
                                     WebSocketUpdateService webSocketUpdateService,
                                     DynamicSessionCalculator sessionCalculator,
                                     AdmissionMetricsService metricsService,
                                     LoadBalancingOptimizer loadBalancer) {
        this.redisTemplate = redisTemplate;
        this.webSocketUpdateService = webSocketUpdateService;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    @Scheduled(fixedRate = 2000)
    public void processExpiredSessions() { // ★ 메서드 이름 변경: 역할 명확화
        if (sessionTimeoutSeconds <= 0) return;

        long startTime = System.currentTimeMillis();
        long expirationTime = startTime - (sessionTimeoutSeconds * 1000);
        
        ScanOptions options = ScanOptions.scanOptions()
                .match("active_sessions:movie:*")
                .count(100)
                .build();

        int totalProcessedMovies = 0;
        int totalTimeouts = 0;
        
        var connFactory = redisTemplate.getConnectionFactory();
        if (connFactory == null) {
            logger.warn("Redis 연결 팩토리를 가져올 수 없어 스캔을 건너뜁니다.");
            return;
        }
        
        try (var conn = connFactory.getConnection();
             Cursor<byte[]> cursor = conn.scan(options)) {
            
            while (cursor.hasNext()) {
                String activeSessionsKey = new String(cursor.next(), StandardCharsets.UTF_8);
                String movieId = extractMovieId(activeSessionsKey);
                if (movieId == null) continue;
                
                // 부하 분산 확인: 이 영화의 타임아웃 처리는 내가 담당인가?
                if (!loadBalancer.shouldProcessMovie(movieId)) {
                    continue;
                }
                
                totalProcessedMovies++;
                
                // 만료된 세션 처리
                Set<String> expiredMembers =
                        redisTemplate.opsForZSet().rangeByScore(activeSessionsKey, 0, expirationTime);
                        
                if (expiredMembers != null && !expiredMembers.isEmpty()) {
                    redisTemplate.opsForZSet().removeRangeByScore(activeSessionsKey, 0, expirationTime);
                    int expiredCount = expiredMembers.size();
                    totalTimeouts += expiredCount;
                    logger.info("[{}] 만료된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, expiredCount);
                    
                    // 타임아웃 알림 전송
                    expiredMembers.forEach(member -> {
                        if (member.contains(":")) {
                            String requestId = member.split(":", 2)[0];
                            webSocketUpdateService.notifyTimeout(requestId);
                        }
                    });
                    
                    // 메트릭 기록
                    metricsService.recordTimeout(movieId, expiredCount);
                    
                    // ★★★ 핵심 변경: 새로운 사용자를 입장시키는 로직을 완전히 제거 ★★★
                    // 이 클래스는 이제 빈자리를 만들기만 합니다.
                    // 채우는 것은 QueueProcessor의 역할입니다.
                }
            }
        } catch (Exception e) {
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
        
        // Pod 부하 업데이트
        if (totalProcessedMovies > 0) {
            loadBalancer.updatePodLoad(totalProcessedMovies);
            long processingTime = System.currentTimeMillis() - startTime;
            logger.debug("타임아웃 처리 완료 - 처리 영화: {}, 총 타임아웃: {}, 소요시간: {}ms",
                    totalProcessedMovies, totalTimeouts, processingTime);
        }
    }

    private String extractMovieId(String key) {
        Pattern pattern = Pattern.compile("active_sessions:movie:(.+)");
        Matcher matcher = pattern.matcher(key);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }
}