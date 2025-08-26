package com.example.admission;

import com.example.admission.ws.WebSocketUpdateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
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
    
    @Value("${admission.kinesis.stream-name:cgv-admission-queue}")
    private String streamName;
    
    @Value("${admission.kinesis.region:ap-northeast-2}")
    private String region;
    
    @Value("${admission.kinesis.consumer.poll-interval:5000}")
    private long pollInterval;
    
    private KinesisClient kinesisClient;
    private ScheduledExecutorService consumerExecutor;
    private final WebSocketUpdateService webSocketService;
    private String shardIterator;
    private volatile boolean isRunning = false;

    public KinesisAdmissionConsumer(WebSocketUpdateService webSocketService) {
        this.webSocketService = webSocketService;
    }

    @PostConstruct
    public void init() {
        this.kinesisClient = KinesisClient.builder()
                .region(Region.of(region))
                .build();
        
        this.consumerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KinesisConsumerThread");
            t.setDaemon(true);
            return t;
        });
        
        startConsumer();
    }

    private void startConsumer() {
        try {
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                    .streamName(streamName)
                    .build();
            
            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            List<Shard> shards = describeResponse.streamDescription().shards();
            
            if (shards.isEmpty()) {
                logger.warn("Kinesis 스트림에 샤드가 없습니다: {}", streamName);
                return;
            }
            
            Shard shard = shards.get(0);
            GetShardIteratorRequest shardIteratorRequest = GetShardIteratorRequest.builder()
                    .streamName(streamName)
                    .shardId(shard.shardId())
                    .shardIteratorType(ShardIteratorType.LATEST)
                    .build();
            
            GetShardIteratorResponse shardIteratorResponse = kinesisClient.getShardIterator(shardIteratorRequest);
            this.shardIterator = shardIteratorResponse.shardIterator();
            
            this.isRunning = true;
            
            consumerExecutor.scheduleWithFixedDelay(this::pollRecords, 0, pollInterval, TimeUnit.MILLISECONDS);
            
            logger.info("CONSUMER: Kinesis 컨슈머 시작됨 | streamName: {} | shardId: {} | pollInterval: {}ms", 
                       streamName, shard.shardId(), pollInterval);
            
        } catch (Exception e) {
            logger.error("CONSUMER: Kinesis 컨슈머 초기화 실패", e);
        }
    }

    private void pollRecords() {
        if (!isRunning || shardIterator == null) {
            return;
        }
        
        try {
            GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                    .shardIterator(shardIterator)
                    .limit(100)
                    .build();
            
            GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
            List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();
            
            this.shardIterator = getRecordsResponse.nextShardIterator();
            
            if (!records.isEmpty()) {
                logger.info("CONSUMER: {} 개의 레코드 수신", records.size());
                
                for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
                    processAdmissionEvent(record);
                }
            }
            
        } catch (Exception e) {
            logger.error("CONSUMER: 레코드 폴링 중 오류 발생", e);
            
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processAdmissionEvent(software.amazon.awssdk.services.kinesis.model.Record record) {
        try {
            SdkBytes data = record.data();
            String jsonPayload = data.asString(StandardCharsets.UTF_8);
            
            logger.debug("CONSUMER: 레코드 처리 시작 | sequenceNumber: {} | data: {}", 
                        record.sequenceNumber(), jsonPayload);
            
            JsonNode eventNode = objectMapper.readTree(jsonPayload);
            String eventType = eventNode.path("eventType").asText();
            
            if ("ADMISSION_GRANTED".equals(eventType)) {
                String requestId = eventNode.path("requestId").asText();
                String movieId = eventNode.path("movieId").asText();
                String sessionId = eventNode.path("sessionId").asText();
                long timestamp = eventNode.path("timestamp").asLong();
                
                logger.info("CONSUMER: 입장 허가 이벤트 처리 | requestId: {} | movieId: {} | sessionId: {}", 
                           requestId, movieId, sessionId);
                
                // WebSocket을 통해 해당 사용자에게 입장 허가 알림
                webSocketService.notifyAdmission(requestId, movieId);
                
                logger.info("CONSUMER: 입장 허가 알림 전송 완료 | requestId: {} | movieId: {}", 
                           requestId, movieId);
                
            } else {
                logger.warn("CONSUMER: 알 수 없는 이벤트 타입: {}", eventType);
            }
            
        } catch (Exception e) {
            logger.error("CONSUMER: 입장 이벤트 처리 실패 | sequenceNumber: {}", 
                        record.sequenceNumber(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("CONSUMER: Kinesis 컨슈머 종료 중...");
        
        this.isRunning = false;
        
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (kinesisClient != null) {
            kinesisClient.close();
        }
        
        logger.info("CONSUMER: Kinesis 컨슈머 종료 완료");
    }
}