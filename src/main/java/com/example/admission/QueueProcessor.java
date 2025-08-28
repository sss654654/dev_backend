// ===============================================
// QueueProcessor.java - 컴파일 오류 해결 버전
// ===============================================
package com.example.admission.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

@Component
public class QueueProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    // 🔥 부하 상황 대응 설정
    private static final int PROCESSING_BATCH_SIZE = 100;  // 배치 크기 증가
    private static final long PROCESSING_INTERVAL = 2000;  // 처리 간격을 2초로 단축
    private static final int MAX_WEBSOCKET_RETRIES = 2;    // WebSocket 재시도 제한
    
    private final AdmissionService admissionService;
    private final SimpMessagingTemplate messagingTemplate;

    public QueueProcessor(AdmissionService admissionService,
                         SimpMessagingTemplate messagingTemplate) {
        this.admissionService = admissionService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 🔥 개선된 스케줄러 - 2초마다 모든 대기열 처리
     */
    @Scheduled(fixedDelay = PROCESSING_INTERVAL)
    public void processAllQueues() {
        try {
            logger.debug("🔄 대기열 처리 시작");
            
            // 🔥 하드코딩된 활성 영화 목록 (MovieService 대신)
            List<String> activeMovieIds = getActiveMovieIds();
            
            // 🔥 병렬 처리로 성능 향상
            activeMovieIds.parallelStream()
                .forEach(movieId -> {
                    try {
                        processMovieQueue("movie", movieId);
                    } catch (Exception e) {
                        logger.warn("영화 대기열 처리 실패: {}", movieId, e);
                    }
                });
                
        } catch (Exception e) {
            logger.error("대기열 처리 중 전체 오류 발생", e);
        }
    }

    /**
     * 🔥 활성 영화 ID 목록 조회 (MovieService 대신 임시 구현)
     * TODO: MovieService가 구현되면 이 메서드를 대체하세요
     */
    private List<String> getActiveMovieIds() {
        // 현재 시스템에서 사용 중인 영화 ID들
        return Arrays.asList(
            "movie-topgun2",
            "movie-avatar2", 
            "movie-blackpanther2",
            "movie-spider-verse",
            "movie-dune2"
        );
    }

    /**
     * 🔥 개별 영화 대기열 처리 (성능 최적화)
     */
    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            logger.debug("영화 {} 처리: 빈자리={}, 대기자={}", movieId, vacantSlots, waitingCount);
            
            boolean admitted = false;
            if (vacantSlots > 0 && waitingCount > 0) {
                // 🔥 배치 크기 제한으로 한 번에 너무 많이 처리하지 않음
                long admitCount = Math.min(vacantSlots, 
                                         Math.min(waitingCount, PROCESSING_BATCH_SIZE));
                
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    admitted = true;
                    logger.info("영화 {} - {}명 입장 처리 완료", movieId, admittedUsers.size());
                    
                    // 🔥 WebSocket 전송 성능 개선
                    sendWebSocketNotificationsOptimized(admittedUsers, type, movieId);
                }
            }
            
            // 🔥 통계 전송 (입장자가 있거나 대기자가 있을 때만)
            if (admitted || waitingCount > 0) {
                sendQueueStatsOptimized(type, movieId);
            }
            
        } catch (Exception e) {
            logger.error("대기열 처리 중 오류: {} {}", type, movieId, e);
        }
    }

    /**
     * 🔥 최적화된 WebSocket 개별 알림 전송
     * 병렬 처리 + 재시도 로직 + 점진적 백오프
     */
    private void sendWebSocketNotificationsOptimized(List<String> admittedUsers, String type, String movieId) {
        // 개별 알림을 병렬로 비동기 전송 (부하 분산)
        admittedUsers.parallelStream()
            .forEach(requestId -> {
                CompletableFuture.runAsync(() -> {
                    int retryCount = 0;
                    while (retryCount < MAX_WEBSOCKET_RETRIES) {
                        try {
                            Map<String, Object> admitMessage = Map.of(
                                "status", "ADMITTED",
                                "type", type,
                                "id", movieId,
                                "requestId", requestId,
                                "timestamp", System.currentTimeMillis()
                            );
                            
                            messagingTemplate.convertAndSend(
                                "/topic/admit/" + requestId,
                                admitMessage
                            );
                            
                            logger.debug("입장 알림 전송 성공: {}", requestId);
                            break; // 성공시 루프 탈출
                            
                        } catch (Exception e) {
                            retryCount++;
                            logger.warn("WebSocket 알림 전송 실패 ({}/{}): {}", 
                                    retryCount, MAX_WEBSOCKET_RETRIES, requestId, e);
                            
                            if (retryCount >= MAX_WEBSOCKET_RETRIES) {
                                logger.error("WebSocket 알림 전송 최종 실패: {}", requestId);
                                // TODO: 실패한 알림을 DB나 Queue에 저장하여 나중에 재처리
                                // 예: failedNotificationService.save(requestId, admitMessage);
                            }
                            
                            try {
                                // 점진적 백오프: 100ms, 200ms, 300ms...
                                Thread.sleep(100 * retryCount);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                });
            });
    }

    /**
     * 🔥 최적화된 통계 전송 (부하 감소)
     * 비동기 처리로 메인 스레드 블로킹 방지
     */
    private void sendQueueStatsOptimized(String type, String movieId) {
        try {
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            long activeCount = admissionService.getTotalActiveCount(type, movieId);
            
            // 통계 전송도 비동기로 처리 (메인 처리 로직과 분리)
            CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> stats = Map.of(
                        "totalWaiting", waitingCount,
                        "activeCount", activeCount,
                        "movieId", movieId,
                        "type", type,
                        "timestamp", System.currentTimeMillis()
                    );
                    
                    // 영화별 통계 토픽으로 전송
                    messagingTemplate.convertAndSend("/topic/stats/" + movieId, stats);
                    
                    logger.debug("통계 전송 완료: {} (대기={}, 활성={})", 
                               movieId, waitingCount, activeCount);
                    
                } catch (Exception e) {
                    logger.warn("통계 전송 실패: {} {}", type, movieId, e);
                }
            });
            
        } catch (Exception e) {
            logger.error("통계 조회 실패: {} {}", type, movieId, e);
        }
    }

    /**
     * 🔥 수동 대기열 처리 트리거 (관리자용 또는 긴급상황용)
     */
    public void forceProcessQueue(String type, String movieId) {
        logger.info("🔧 수동 대기열 처리 실행: {} {}", type, movieId);
        try {
            processMovieQueue(type, movieId);
        } catch (Exception e) {
            logger.error("수동 대기열 처리 실패: {} {}", type, movieId, e);
            throw new RuntimeException("수동 처리 실패", e);
        }
    }

    /**
     * 🔥 시스템 상태 체크 (헬스체크용)
     */
    public Map<String, Object> getProcessorStatus() {
        try {
            List<String> activeMovies = getActiveMovieIds();
            long totalWaiting = activeMovies.stream()
                .mapToLong(movieId -> {
                    try {
                        return admissionService.getTotalWaitingCount("movie", movieId);
                    } catch (Exception e) {
                        logger.warn("대기자 수 조회 실패: {}", movieId, e);
                        return 0L;
                    }
                })
                .sum();
            
            long totalActive = activeMovies.stream()
                .mapToLong(movieId -> {
                    try {
                        return admissionService.getTotalActiveCount("movie", movieId);
                    } catch (Exception e) {
                        logger.warn("활성 세션 수 조회 실패: {}", movieId, e);
                        return 0L;
                    }
                })
                .sum();

            return Map.of(
                "processingInterval", PROCESSING_INTERVAL,
                "batchSize", PROCESSING_BATCH_SIZE,
                "activeMovies", activeMovies.size(),
                "totalWaitingUsers", totalWaiting,
                "totalActiveUsers", totalActive,
                "lastProcessedAt", System.currentTimeMillis(),
                "status", "HEALTHY"
            );
            
        } catch (Exception e) {
            logger.error("프로세서 상태 조회 실패", e);
            return Map.of(
                "status", "ERROR",
                "error", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
        }
    }
}