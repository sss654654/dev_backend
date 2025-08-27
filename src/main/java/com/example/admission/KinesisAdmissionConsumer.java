// KinesisAdmissionConsumer.java 전체 교체

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
import java.util.concurrent.atomic.AtomicLong;

@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${KINESIS_STREAM_NAME:prod-cgv-admissions-stream}")
    private String streamName;
    
    @Value("${admission.kinesis.region:ap-northeast-2}")
    private String region;
    
    @Value("${KINESIS_POLL_INTERVAL:1000}") // 1초로 단축
    private long pollInterval;
    
    @Value("${admission.kinesis.consumer.enabled:true}")
    private boolean consumerEnabled;
    
    private KinesisClient kinesisClient;
    private ScheduledExecutorService consumerExecutor;
    private final WebSocketUpdateService webSocketService;
    private String shardIterator;
    private volatile boolean isRunning = false;
    private final AtomicLong processedCount = new AtomicLong(0);

    public KinesisAdmissionConsumer(WebSocketUpdateService webSocketService) {
        this.webSocketService = webSocketService;
    }

    @PostConstruct
    public void init() {
        if (!consumerEnabled) {
            logger.info("CONSUMER: Kinesis 컨슈머가 비활성화되어 있습니다.");
            return;
        }
        
        try {
            this.kinesisClient = KinesisClient.builder()
                    .region(Region.of(region))
                    .build();
            
            this.consumerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "KinesisConsumerThread");
                t.setDaemon(true);
                return t;
            });
            
            logger.info("CONSUMER: Kinesis 컨슈머 초기화 완료 - 스트림: {}, 리전: {}, 폴링간격: {}ms", 
                       streamName, region, pollInterval);
            
            startConsumer();
            
        } catch (Exception e) {
            logger.error("CONSUMER: Kinesis 컨슈머 초기화 실패", e);
        }
    }

    private void startConsumer() {
        try {
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                    .streamName(streamName)
                    .build();
                    
            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            
            if (describeResponse.streamDescription().shards().isEmpty()) {
                logger.error("CONSUMER: 스트림에 샤드가 없습니다: {}", streamName);
                return;
            }
            
            Shard shard = describeResponse.streamDescription().shards().get(0);
            String shardId = shard.shardId();
            
            // 중요: TRIM_HORIZON으로 변경하여 모든 메시지를 받도록 함
            GetShardIteratorRequest shardIteratorRequest = GetShardIteratorRequest.builder()
                    .streamName(streamName)
                    .shardId(shardId)
                    .shardIteratorType(ShardIteratorType.TRIM_HORIZON) // 처음부터 읽기
                    .build();
                    
            GetShardIteratorResponse shardIteratorResponse = kinesisClient.getShardIterator(shardIteratorRequest);
            this.shardIterator = shardIteratorResponse.shardIterator();
            
            logger.info("CONSUMER: 샤드 이터레이터 초기화 완료 - 샤드ID: {}", shardId);
            
            this.isRunning = true;
            consumerExecutor.scheduleWithFixedDelay(
                this::pollRecords, 
                0, 
                pollInterval, 
                TimeUnit.MILLISECONDS
            );
            
            logger.info("CONSUMER: Kinesis 레코드 폴링 시작됨 ({}ms 간격)", pollInterval);
            
        } catch (Exception e) {
            logger.error("CONSUMER: 컨슈머 시작 실패", e);
        }
    }

    private void pollRecords() {
        if (!isRunning || shardIterator == null) {
            logger.debug("CONSUMER: 폴링 건너뜀 - running: {}, shardIterator: {}", 
                        isRunning, shardIterator != null ? "존재" : "null");
            return;
        }
        
        try {
            GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                    .shardIterator(shardIterator)
                    .limit(100)
                    .build();
                    
            GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
            List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();
            
            if (!records.isEmpty()) {
                logger.info("CONSUMER: {}개의 Kinesis 레코드 수신됨", records.size());
                
                for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
                    processRecord(record);
                }
                
                logger.info("CONSUMER: {}개의 레코드 처리 완료", records.size());
            } else {
                logger.debug("CONSUMER: 수신된 레코드 없음");
            }
            
            // 다음 폴링을 위해 이터레이터 업데이트
            this.shardIterator = getRecordsResponse.nextShardIterator();
            
            if (shardIterator == null) {
                logger.warn("CONSUMER: 샤드 이터레이터가 null입니다. 재초기화 필요");
                // 재초기화 시도
                startConsumer();
            }
            
        } catch (Exception e) {
            logger.error("CONSUMER: 레코드 폴링 중 오류 발생", e);
            
            // 오류 발생 시 잠시 대기 후 재시도
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                isRunning = false;
            }
        }
    }

    private void processRecord(software.amazon.awssdk.services.kinesis.model.Record record) {
    try {
        String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
        
        logger.info("CONSUMER: 레코드 처리 시작 | 시퀀스번호: {} | 데이터: {}", 
                   record.sequenceNumber(), data);
        
        JsonNode eventNode = objectMapper.readTree(data);
        String eventType = eventNode.path("action").asText();
        
        // ✅ 기존: "ADMIT"만 처리
        // ✅ 수정: "ADMIT"와 "enter" 모두 처리
        if ("ADMIT".equals(eventType)) {
            // 정상적인 입장 허가 이벤트 처리
            String requestId = eventNode.path("requestId").asText();
            String movieId = eventNode.path("movieId").asText();
            String sessionId = eventNode.path("sessionId").asText();
            
            webSocketService.notifyAdmission(requestId, movieId);
            logger.info("CONSUMER: 입장 허가 처리 완료 - requestId: {}", requestId);
            
        } 
        else {
            logger.warn("CONSUMER: 알 수 없는 이벤트 타입: {} | 데이터: {}", eventType, data);
        }
        
    } catch (Exception e) {
        logger.error("CONSUMER: 레코드 처리 실패 | 시퀀스번호: {}", 
                    record.sequenceNumber(), e);
    }
}

    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isEnabled() {
        return consumerEnabled;
    }
    
    public String getStreamName() {
        return streamName;
    }
    
    public long getProcessedCount() {
        return processedCount.get();
    }

    @PreDestroy
    public void shutdown() {
        logger.info("CONSUMER: Kinesis 컨슈머 종료 시작...");
        
        this.isRunning = false;
        
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("CONSUMER: 정상 종료 시간 초과, 강제 종료 실행");
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("CONSUMER: 종료 대기 중 인터럽트 발생, 강제 종료 실행");
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (kinesisClient != null) {
            try {
                kinesisClient.close();
                logger.info("CONSUMER: Kinesis 클라이언트 종료 완료");
            } catch (Exception e) {
                logger.error("CONSUMER: Kinesis 클라이언트 종료 중 오류", e);
            }
        }
        
        logger.info("CONSUMER: Kinesis 컨슈머 종료 완료 - 총 처리: {}건", processedCount.get());
    }
}