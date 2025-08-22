package com.example.admission;

import com.example.websockets.LiveUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NotificationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationConsumer.class);
    
    private final KinesisClient kinesisClient;
    private final LiveUpdateService liveUpdateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 스레드 관리
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kinesis-consumer");
        t.setDaemon(true);
        return t;
    });
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public NotificationConsumer(KinesisClient kinesisClient, LiveUpdateService liveUpdateService) {
        this.kinesisClient = kinesisClient;
        this.liveUpdateService = liveUpdateService;
    }

    private void handleRecord(String data) {
        try {
            Map<String, Object> message = objectMapper.readValue(data, Map.class);
            String action = (String) message.get("action");
            String sessionId = (String) message.get("sessionId");
            String movieId = (String) message.get("movieId");
            String requestId = (String) message.get("requestId");
            Long timestamp = message.get("timestamp") != null ? 
                ((Number) message.get("timestamp")).longValue() : null;

            if ("ADMIT".equals(action) && sessionId != null && movieId != null && requestId != null) {
                logger.info("CONSUMER: Kinesis로부터 [{}:{}] 입장 이벤트를 수신했습니다. (requestId: {}, timestamp: {})", 
                    movieId, sessionId, requestId, timestamp);
                
                // WebSocket으로 클라이언트에게 알림
                liveUpdateService.notifyAdmitted(requestId, movieId, sessionId);
                
                processedCount.incrementAndGet();
            } else {
                logger.warn("유효하지 않은 이벤트 데이터: {}", data);
            }
            
        } catch (Exception e) {
            logger.error("Kinesis 메시지 처리 또는 WebSocket 전송 실패: data={}", data, e);
            errorCount.incrementAndGet();
        }
    }

    @PostConstruct
    public void startConsuming() {
        if (running.compareAndSet(false, true)) {
            logger.info("Kinesis Consumer 시작: streamName={}", streamName);
            
            executorService.submit(() -> {
                try {
                    // 스트림 초기화 대기
                    Thread.sleep(5000);
                    
                    // 스트림 상태 확인
                    DescribeStreamResponse streamDescription = kinesisClient.describeStream(
                        DescribeStreamRequest.builder().streamName(streamName).build()
                    );
                    
                    if (streamDescription.streamDescription().streamStatus() != StreamStatus.ACTIVE) {
                        logger.warn("Kinesis 스트림이 ACTIVE 상태가 아닙니다: {}", 
                            streamDescription.streamDescription().streamStatus());
                        return;
                    }
                    
                    // 첫 번째 샤드에서 소비 시작
                    List<Shard> shards = kinesisClient.listShards(
                        ListShardsRequest.builder().streamName(streamName).build()
                    ).shards();
                    
                    if (shards.isEmpty()) {
                        logger.error("사용 가능한 샤드가 없습니다: streamName={}", streamName);
                        return;
                    }
                    
                    String shardId = shards.get(0).shardId();
                    logger.info("Kinesis Consumer 샤드 연결: shardId={}", shardId);
                    
                    // 샤드 반복자 초기화 (LATEST = 새로운 레코드만 읽기)
                    final AtomicReference<String> shardIteratorRef = new AtomicReference<>(
                        kinesisClient.getShardIterator(GetShardIteratorRequest.builder()
                            .streamName(streamName)
                            .shardId(shardId)
                            .shardIteratorType(ShardIteratorType.LATEST)
                            .build()).shardIterator()
                    );

                    // 메인 소비 루프
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            String currentIterator = shardIteratorRef.get();
                            if (currentIterator == null) {
                                logger.warn("Shard iterator가 null입니다. 재연결 시도...");
                                Thread.sleep(5000);
                                continue;
                            }
                            
                            GetRecordsResponse response = kinesisClient.getRecords(
                                GetRecordsRequest.builder()
                                    .shardIterator(currentIterator)
                                    .limit(100) // 배치 크기 제한
                                    .build()
                            );
                            
                            // 레코드 처리
                            List<Record> records = response.records();
                            if (!records.isEmpty()) {
                                logger.debug("Kinesis 레코드 수신: {} 개", records.size());
                                for (Record record : records) {
                                    String data = record.data().asUtf8String();
                                    handleRecord(data);
                                }
                            }
                            
                            // 다음 반복자 업데이트
                            shardIteratorRef.set(response.nextShardIterator());
                            
                            // CPU 사용률 조절
                            Thread.sleep(1000);
                            
                            // 주기적으로 통계 로그
                            if (processedCount.get() % 100 == 0 && processedCount.get() > 0) {
                                logger.info("Kinesis Consumer 통계 - 처리: {}, 에러: {}", 
                                    processedCount.get(), errorCount.get());
                            }
                            
                        } catch (ProvisionedThroughputExceededException e) {
                            logger.warn("Kinesis 처리량 한계 도달. 대기 시간 증가...");
                            Thread.sleep(5000);
                        } catch (ExpiredIteratorException e) {
                            logger.warn("Iterator 만료. 새로운 iterator 요청...");
                            shardIteratorRef.set(
                                kinesisClient.getShardIterator(GetShardIteratorRequest.builder()
                                    .streamName(streamName)
                                    .shardId(shardId)
                                    .shardIteratorType(ShardIteratorType.LATEST)
                                    .build()).shardIterator()
                            );
                        } catch (Exception e) {
                            logger.error("Kinesis 레코드 처리 중 오류", e);
                            errorCount.incrementAndGet();
                            Thread.sleep(2000);
                        }
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("Kinesis consumer 스레드가 중단되었습니다.");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    logger.error("Kinesis consumer 치명적 오류", e);
                } finally {
                    running.set(false);
                    logger.info("Kinesis Consumer 종료됨");
                }
            });
        }
    }
    
    @PreDestroy
    public void stopConsuming() {
        logger.info("Kinesis Consumer 종료 시작...");
        running.set(false);
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Kinesis Consumer 완전 종료됨");
    }
    
    /**
     * 헬스체크용 메서드
     */
    public Map<String, Object> getHealthStatus() {
        Map<String, Object> health = new java.util.HashMap<>();
        health.put("running", running.get());
        health.put("processedCount", processedCount.get());
        health.put("errorCount", errorCount.get());
        health.put("errorRate", processedCount.get() > 0 ? 
            (double) errorCount.get() / processedCount.get() : 0.0);
        return health;
    }
}