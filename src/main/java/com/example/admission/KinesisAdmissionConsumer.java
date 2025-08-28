// src/main/java/com/example/admission/KinesisAdmissionConsumer.java
package com.example.admission;

import com.example.admission.ws.WebSocketUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${KINESIS_STREAM_NAME:prod-cgv-admissions-stream}")
    private String streamName;
    
    @Value("${KINESIS_CONSUMER_ENABLED:true}")
    private boolean consumerEnabled;
    
    private final KinesisClient kinesisClient;
    private ScheduledExecutorService consumerExecutor;
    private final WebSocketUpdateService webSocketService;
    private String shardIterator;
    private volatile boolean isRunning = false;

    public KinesisAdmissionConsumer(WebSocketUpdateService webSocketService, KinesisClient kinesisClient) {
        this.webSocketService = webSocketService;
        this.kinesisClient = kinesisClient;
    }

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            logger.info("Kinesis 컨슈머가 비활성화되어 있습니다.");
            return;
        }
        this.consumerExecutor = Executors.newSingleThreadScheduledExecutor();
        startConsumer();
    }

    private void startConsumer() {
        try {
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder().streamName(streamName).build();
            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            Shard shard = describeResponse.streamDescription().shards().get(0);
            
            GetShardIteratorRequest shardIteratorRequest = GetShardIteratorRequest.builder()
                    .streamName(streamName)
                    .shardId(shard.shardId())
                    .shardIteratorType(ShardIteratorType.LATEST) // 항상 최신 데이터부터 읽기 시작
                    .build();
            this.shardIterator = kinesisClient.getShardIterator(shardIteratorRequest).shardIterator();
            
            this.isRunning = true;
            long pollInterval = 1000; // 1초
            consumerExecutor.scheduleWithFixedDelay(this::pollRecords, 0, pollInterval, TimeUnit.MILLISECONDS);
            logger.info("Kinesis 레코드 폴링 시작됨 ({}ms 간격)", pollInterval);
            
        } catch (Exception e) {
            logger.error("Kinesis 컨슈머 시작 실패", e);
        }
    }

    private void pollRecords() {
        if (!isRunning || shardIterator == null) return;
        try {
            GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder().shardIterator(shardIterator).limit(100).build();
            GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
            List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();
            
            if (!records.isEmpty()) {
                records.forEach(this::processRecord);
            }
            this.shardIterator = getRecordsResponse.nextShardIterator();
        } catch (ResourceNotFoundException e) {
             logger.error("Kinesis 스트림을 찾을 수 없습니다: {}", streamName, e);
             this.isRunning = false; // 스트림이 없으면 중지
        } catch (Exception e) {
            logger.error("레코드 폴링 중 오류 발생, 재시도합니다.", e);
        }
    }

    private void processRecord(software.amazon.awssdk.services.kinesis.model.Record record) {
        try {
            String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
            JsonNode eventNode = objectMapper.readTree(data);
            String eventType = eventNode.path("action").asText();
            
            // ⭐ [핵심] 모든 이벤트 타입을 처리하도록 수정
            switch (eventType) {
                case "ADMIT":
                    webSocketService.notifyAdmission(eventNode.path("requestId").asText(), eventNode.path("movieId").asText());
                    break;
                case "RANK_UPDATE":
                    webSocketService.notifyRankUpdate(eventNode.path("requestId").asText(), "WAITING", eventNode.path("rank").asLong(), eventNode.path("totalWaiting").asLong());
                    break;
                case "STATS_UPDATE":
                    webSocketService.broadcastQueueStats(eventNode.path("movieId").asText(), eventNode.path("totalWaiting").asLong());
                    break;
                default:
                    logger.warn("알 수 없는 Kinesis 이벤트 타입 수신: {}", eventType);
                    break;
            }
        } catch (Exception e) {
            logger.error("Kinesis 레코드 처리 실패", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        this.isRunning = false;
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
        }
    }
}