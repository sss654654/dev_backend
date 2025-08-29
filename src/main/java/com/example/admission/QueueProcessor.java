// ===============================================
// QueueProcessor.java - 최종 토픽 통일 버전
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
    private static final int PROCESSING_BATCH_SIZE = 500;
    private static final long PROCESSING_INTERVAL = 500;
    private static final int MAX_WEBSOCKET_RETRIES = 2;
    
    private final AdmissionService admissionService;
    private final SimpMessagingTemplate messagingTemplate;

    public QueueProcessor(AdmissionService admissionService,
                         SimpMessagingTemplate messagingTemplate) {
        this.admissionService = admissionService;
        this.messagingTemplate = messagingTemplate;
    }

    @Scheduled(fixedDelay = PROCESSING_INTERVAL)
    public void processAllQueues() {
        try {
            logger.debug("🔄 대기열 처리 시작");
            
            List<String> activeMovieIds = getActiveMovieIds();
            
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

    private List<String> getActiveMovieIds() {
        return Arrays.asList(
            "movie-topgun2",
            "movie-avatar2", 
            "movie-blackpanther2",
            "movie-spider-verse",
            "movie-dune2"
        );
    }

    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            logger.debug("영화 {} 처리: 빈자리={}, 대기자={}", movieId, vacantSlots, waitingCount);
            
            boolean admitted = false;
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, 
                                         Math.min(waitingCount, PROCESSING_BATCH_SIZE));
                
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    admitted = true;
                    logger.info("영화 {} - {}명 입장 처리 완료", movieId, admittedUsers.size());
                    
                    // 🔥 프론트엔드와 호환되도록 토픽 수정
                    sendWebSocketNotificationsOptimized(admittedUsers, type, movieId);
                }
            }
            
            if (admitted || waitingCount > 0) {
                sendQueueStatsOptimized(type, movieId);
            }
            
        } catch (Exception e) {
            logger.error("대기열 처리 중 오류: {} {}", type, movieId, e);
        }
    }

    /**
     * 🔥 토픽 통일: 프론트엔드 WaitPage.jsx와 호환되도록 수정
     */
    private void sendWebSocketNotificationsOptimized(List<String> admittedUsers, String type, String movieId) {
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
                                "action", "REDIRECT_TO_SEATS",
                                "message", "🎉 입장이 허가되었습니다! 좌석 선택 페이지로 이동합니다.",
                                "timestamp", System.currentTimeMillis()
                            );
                            
                            // 🔥 중요: WebSocketUpdateService와 동일한 토픽 사용
                            messagingTemplate.convertAndSend(
                                "/topic/admission/" + requestId,  // admit → admission으로 변경
                                admitMessage
                            );
                            
                            logger.debug("입장 알림 전송 성공: {} → /topic/admission/{}", requestId, requestId);
                            break;
                            
                        } catch (Exception e) {
                            retryCount++;
                            logger.warn("WebSocket 알림 전송 실패 ({}/{}): {}", 
                                    retryCount, MAX_WEBSOCKET_RETRIES, requestId, e);
                            
                            if (retryCount >= MAX_WEBSOCKET_RETRIES) {
                                logger.error("WebSocket 알림 전송 최종 실패: {}", requestId);
                            }
                            
                            try {
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
     * 🔥 토픽 통일: 프론트엔드 WaitPage.jsx와 호환되도록 수정
     */
    private void sendQueueStatsOptimized(String type, String movieId) {
        try {
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            long activeCount = admissionService.getTotalActiveCount(type, movieId);
            
            CompletableFuture.runAsync(() -> {
                try {
                    Map<String, Object> stats = Map.of(
                        "totalWaiting", waitingCount,
                        "activeCount", activeCount,
                        "movieId", movieId,
                        "type", type,
                        "timestamp", System.currentTimeMillis()
                    );
                    
                    // 🔥 중요: WebSocketUpdateService와 동일한 토픽 사용
                    messagingTemplate.convertAndSend("/topic/stats/movie/" + movieId, stats);
                    
                    logger.debug("통계 전송 완료: {} (대기={}, 활성={}) → /topic/stats/movie/{}", 
                               movieId, waitingCount, activeCount, movieId);
                    
                } catch (Exception e) {
                    logger.warn("통계 전송 실패: {} {}", type, movieId, e);
                }
            });
            
        } catch (Exception e) {
            logger.error("통계 조회 실패: {} {}", type, movieId, e);
        }
    }

    /**
     * 수동 대기열 처리 트리거
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
     * 시스템 상태 체크
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
                "status", "HEALTHY",
                "websocketTopics", Map.of(
                    "admission", "/topic/admission/{requestId}",
                    "stats", "/topic/stats/movie/{movieId}"
                )
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