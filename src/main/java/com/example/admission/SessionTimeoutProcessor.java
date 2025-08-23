package com.example.admission;

import com.example.admission.service.AdmissionService; // AdmissionService 임포트
import com.example.admission.ws.WebSocketUpdateService; // WebSocketUpdateService 임포트
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

@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);
    
    private final StringRedisTemplate redisTemplate;
    private final AdmissionService admissionService; // ★ AdmissionService 주입
    private final WebSocketUpdateService webSocketUpdateService; // ★ WebSocketUpdateService 주입

    @Value("${admission.session-timeout-seconds}")
    private long sessionTimeoutSeconds;

    // ★ 생성자 수정
    public SessionTimeoutProcessor(StringRedisTemplate redisTemplate, AdmissionService admissionService, WebSocketUpdateService webSocketUpdateService) {
        this.redisTemplate = redisTemplate;
        this.admissionService = admissionService;
        this.webSocketUpdateService = webSocketUpdateService;
    }

    @Scheduled(fixedRate = 2000) // ★ 주기를 2초로 줄여 더 실시간처럼 동작하게 함
    public void processExpiredSessionsAndAdmitNext() {
        if (sessionTimeoutSeconds <= 0) return;

        long expirationTime = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
        
        // 'active_sessions:movie:*' 패턴의 모든 키를 스캔
        ScanOptions options = ScanOptions.scanOptions().match("active_sessions:movie:*").count(100).build();
        
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String activeSessionsKey = cursor.next();
                String movieId = extractMovieId(activeSessionsKey);
                if (movieId == null) continue;

                // 1. 만료된 세션들을 조회 및 삭제
                Set<String> expiredMembers = redisTemplate.opsForZSet().rangeByScore(activeSessionsKey, 0, expirationTime);
                if (expiredMembers != null && !expiredMembers.isEmpty()) {
                    redisTemplate.opsForZSet().removeRangeByScore(activeSessionsKey, 0, expirationTime);
                    logger.info("[{}] 만료된 활성 세션 {}개를 정리했습니다.", activeSessionsKey, expiredMembers.size());

                    // 2. 삭제된 각 사용자에게 타임아웃 알림 전송
                    expiredMembers.forEach(member -> {
                        if (member.contains(":")) {
                            String requestId = member.split(":")[0];
                            // notifyTimeout 메서드가 WebSocketUpdateService에 있다고 가정
                            // webSocketUpdateService.notifyTimeout(requestId); 
                        }
                    });

                    // ★★★★★ 핵심 로직 ★★★★★
                    // 3. 만료된 인원수만큼 대기열에서 다음 사용자 즉시 입장 처리
                    admitNextUsers(movieId, expiredMembers.size());
                }
            }
        } catch (Exception e) {
            logger.error("만료된 세션 정리 및 다음 사용자 입장 처리 중 오류 발생", e);
        }
    }

    private void admitNextUsers(String movieId, long count) {
        final String type = "movie";
        logger.info("[{}:{}] 다음 사용자 입장 처리 시작 ({}명)", type, movieId, count);
        Map<String, String> admittedUsers = admissionService.popNextUsersFromQueue(type, movieId, count);

        if (!admittedUsers.isEmpty()) {
            for (Map.Entry<String, String> entry : admittedUsers.entrySet()) {
                String requestId = entry.getKey();
                String sessionId = entry.getValue();
                
                admissionService.addToActiveSessions(type, movieId, sessionId, requestId);
                webSocketUpdateService.notifyAdmitted(requestId);
            }
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