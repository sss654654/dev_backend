// ===============================================
// 🔥 1. KinesisAdmissionConsumer 완전 리팩토링
// ===============================================
package com.example.admission;

import com.example.admission.ws.WebSocketUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${KINESIS_STREAM_NAME:cgv-admissions-stream}")
    private String streamName;
    
    @Value("${KINESIS_CONSUMER_ENABLED:true}")
    private boolean consumerEnabled;
    
    // ⭐ 핵심 수정 1: 멀티 샤드 지원을 위한 구조 변경
    private final Map<String, ScheduledExecutorService> shardConsumers = new ConcurrentHashMap<>();
    private final Map<String, String> shardIterators = new ConcurrentHashMap<>();
    
    private final KinesisClient kinesisClient;
    private final WebSocketUpdateService webSocketService;
    private volatile boolean isRunning = false;

    public KinesisAdmissionConsumer(WebSocketUpdateService webSocketService, KinesisClient kinesisClient) {
        this.webSocketService = webSocketService;
        this.kinesisClient = kinesisClient;
    }

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            logger.info("🚫 Kinesis 컨슈머가 비활성화되어 있습니다.");
            return;
        }
        this.isRunning = true;
        startAllShardConsumers();
    }

    // ⭐ 핵심 수정 2: 모든 샤드를 별도 스레드로 처리
    private void startAllShardConsumers() {
        try {
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();
            DescribeStreamResponse response = kinesisClient.describeStream(describeRequest);
            
            List<Shard> shards = response.streamDescription().shards();
            logger.info("🔍 발견된 샤드 수: {}", shards.size());
            
            for (Shard shard : shards) {
                startConsumerForShard(shard);
            }
            
        } catch (Exception e) {
            logger.error("❌ 샤드 Consumer 시작 실패", e);
        }
    }

    private void startConsumerForShard(Shard shard) {
        String shardId = shard.shardId();
        
        try {
            // 샤드 Iterator 생성
            GetShardIteratorRequest request = GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .shardIteratorType(ShardIteratorType.LATEST)
                .build();
                
            String iterator = kinesisClient.getShardIterator(request).shardIterator();
            shardIterators.put(shardId, iterator);
            
            // 샤드별 전용 Executor 생성
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> 
                new Thread(r, "kinesis-consumer-" + shardId));
            shardConsumers.put(shardId, executor);
            
            // ⭐ 핵심 수정 3: 250ms 간격으로 변경 (초당 4회 = 안전 범위)
            long pollInterval = 250;
            executor.scheduleWithFixedDelay(() -> pollRecordsForShard(shardId), 
                0, pollInterval, TimeUnit.MILLISECONDS);
                
            logger.info("✅ 샤드 Consumer 시작: {} ({}ms 간격)", shardId, pollInterval);
            
        } catch (Exception e) {
            logger.error("❌ 샤드 {} Consumer 시작 실패", shardId, e);
        }
    }

    // ⭐ 핵심 수정 4: 샤드별 독립적 폴링 + 재시도 로직
    private void pollRecordsForShard(String shardId) {
        if (!isRunning) return;
        
        String iterator = shardIterators.get(shardId);
        if (iterator == null) return;
        
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries && isRunning) {
            try {
                GetRecordsRequest request = GetRecordsRequest.builder()
                    .shardIterator(iterator)
                    .limit(25) // 샤드당 적은 양으로 분산 처리
                    .build();
                    
                GetRecordsResponse response = kinesisClient.getRecords(request);
                
                if (!response.records().isEmpty()) {
                    response.records().forEach(this::processRecord);
                    logger.debug("📥 샤드 {} - {}건 처리", shardId, response.records().size());
                }
                
                // Iterator 업데이트
                shardIterators.put(shardId, response.nextShardIterator());
                break; // 성공시 재시도 루프 탈출
                
            } catch (ProvisionedThroughputExceededException e) {
                retryCount++;
                long waitTime = Math.min(1000 * retryCount, 5000); // 최대 5초
                logger.warn("⚠️ 샤드 {} 처리량 초과, {}ms 대기 후 재시도 {}/{}", 
                           shardId, waitTime, retryCount, maxRetries);
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
            } catch (ResourceNotFoundException e) {
                logger.error("❌ Kinesis 스트림을 찾을 수 없음: {}", streamName, e);
                this.isRunning = false;
                return;
                
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    logger.error("❌ 샤드 {} 폴링 최종 실패", shardId, e);
                    return;
                }
                logger.warn("⚠️ 샤드 {} 폴링 오류, 재시도 {}/{}", shardId, retryCount, maxRetries, e);
                try {
                    Thread.sleep(200 * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void processRecord(software.amazon.awssdk.services.kinesis.model.Record record) {
        try {
            String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
            JsonNode eventNode = objectMapper.readTree(data);
            String eventType = eventNode.path("action").asText();
            
            switch (eventType) {
                case "ADMIT":
                    webSocketService.notifyAdmission(
                        eventNode.path("requestId").asText(), 
                        eventNode.path("movieId").asText());
                    break;
                case "RANK_UPDATE":
                    webSocketService.notifyRankUpdate(
                        eventNode.path("requestId").asText(), 
                        "WAITING", 
                        eventNode.path("rank").asLong(), 
                        eventNode.path("totalWaiting").asLong());
                    break;
                case "STATS_UPDATE":
                    webSocketService.broadcastQueueStats(
                        eventNode.path("movieId").asText(), 
                        eventNode.path("totalWaiting").asLong());
                    break;
                default:
                    logger.debug("🤷 알 수 없는 이벤트 타입: {}", eventType);
                    break;
            }
        } catch (Exception e) {
            logger.error("❌ Kinesis 레코드 처리 실패", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("🛑 Kinesis Consumer 종료 시작...");
        this.isRunning = false;
        
        shardConsumers.values().forEach(executor -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("⏰ Executor 정상 종료 실패, 강제 종료 중...");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        
        shardConsumers.clear();
        shardIterators.clear();
        logger.info("✅ Kinesis Consumer 종료 완료");
    }
    
    // 모니터링을 위한 상태 조회 메서드
    public Map<String, Object> getConsumerStats() {
        return Map.of(
            "isRunning", isRunning,
            "activeShardConsumers", shardConsumers.size(),
            "shardIds", shardIterators.keySet()
        );
    }
}