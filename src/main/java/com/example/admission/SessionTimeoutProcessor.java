package com.example.admission;

import com.example.websockets.LiveUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    
    private final StringRedisTemplate redisTemplate;
    private final LiveUpdateService liveUpdateService;

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;
    
    public SessionTimeoutProcessor(StringRedisTemplate redisTemplate, LiveUpdateService liveUpdateService) {
        this.redisTemplate = redisTemplate;
        this.liveUpdateService = liveUpdateService;
    }

    @Scheduled(fixedRate = 10000)
    public void cleanupExpiredSessions() {
        if (sessionTimeoutSeconds <= 0) return;

        long expirationTime = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);

        // --- 변경된 로직 ---
        // 'active_queues'를 참조하는 대신, 'active_sessions:movie:*' 패턴의 모든 키를 직접 스캔합니다.
        // 이 방법은 대기열 유무와 상관없이 모든 활성 세션을 안정적으로 정리할 수 있습니다.
        ScanOptions options = ScanOptions.scanOptions().match("active_sessions:movie:*").count(100).build();
        
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String activeSessionsKey = cursor.next();
                
                // 1. 만료된 세션들을 먼저 조회합니다. (알림을 보내기 위함)
                Set<String> expiredMembers = redisTemplate.opsForZSet().rangeByScore(activeSessionsKey, 0, expirationTime);

                if (expiredMembers != null && !expiredMembers.isEmpty()) {
                    // 2. 만료된 세션들을 실제로 삭제합니다.
                    redisTemplate.opsForZSet().removeRangeByScore(activeSessionsKey, 0, expirationTime);
                    logger.info("[Key: {}] 만료된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, expiredMembers.size());

                    // 3. 삭제된 각 사용자에게 타임아웃 알림을 보냅니다.
                    for (String member : expiredMembers) {
                        if (member.contains(":")) {
                            String requestId = member.split(":")[0];
                            liveUpdateService.notifyTimeout(requestId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("만료된 세션 정리 중 오류 발생", e);
        }
    }
}