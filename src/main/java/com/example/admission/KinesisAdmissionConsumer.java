// src/main/java/com/example/admission/KinesisAdmissionConsumer.java - 메시지 처리 안정성 개선

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
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

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
    
    @Value("${admission.kinesis.stream-name:cgv-admissions-stream}")
    private String streamName;
    
    @Value("${admission.kinesis.region:ap-northeast-2}")
    private String region;
    
    @Value("${admission.kinesis.consumer.poll-interval:2000}") // 2초로 단축
    private long pollInterval;
    
    @Value("${admission.kinesis.consumer.enabled:true}") // 컨슈머 활성화 제어
    private boolean consumerEnabled;
    
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
        if (!consumerEnabled) {
            logger.info("🚫 CONSUMER: Kinesis 컨슈머가 비활성화되어 있습니다.");
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
            
            logger.info("🚀 CONSUMER: Kinesis 컨슈머 초기화 완료 - 스트림: {}, 리전: {}, 폴링간격: {}ms", 
                       streamName, region, pollInterval);
            
            startConsumer();
            
        } catch (Exception e) {
            logger.error("❌ CONSUMER: Kinesis 컨슈머 초기화 실패", e);
        }
    }

    private void startConsumer() {
        try {
            // 스트림 설명 조회
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                    .streamName(streamName)
                    .build();
                    
            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            
            if (describeResponse.streamDescription().shards().isEmpty()) {
                logger.error("❌ CONSUMER: 스트림에 샤드가 없습니다: {}", streamName);
                return;
            }
            
            // 첫 번째 샤드의 최신 레코드부터 읽기 시작
            Shard shard = describeResponse.streamDescription().shards().get(0);
            String shardId = shard.shardId();
            
            GetShardIteratorRequest shardIteratorRequest = GetShardIteratorRequest.builder()
                    .streamName(streamName)
                    .shardId(shardId)
                    .shardIteratorType(ShardIteratorType.LATEST) // 최신 레코드부터 읽기
                    .build();
                    
            GetShardIteratorResponse shardIteratorResponse = kinesisClient.getShardIterator(shardIteratorRequest);
            this.shardIterator = shardIteratorResponse.shardIterator();
            
            logger.info("✅ CONSUMER: 샤드 이터레이터 초기화 완료 - 샤드ID: {}", shardId);
            
            // 🔧 폴링 시작
            this.isRunning = true;
            consumerExecutor.scheduleWithFixedDelay(
                this::pollRecords, 
                0, 
                pollInterval, 
                TimeUnit.MILLISECONDS
            );
            
            logger.info("🔄 CONSUMER: Kinesis 레코드 폴링 시작됨 ({}ms 간격)", pollInterval);
            
        } catch (Exception e) {
            logger.error("❌ CONSUMER: 컨슈머 시작 실패", e);
        }
    }

    /**
     * 🔄 [핵심] Kinesis에서 레코드를 폴링하고 처리합니다
     */
    private void pollRecords() {
        if (!isRunning || shardIterator == null) {
            return;
        }
        
        try {
            GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                    .shardIterator(shardIterator)
                    .limit(100) // 한 번에 최대 100개 레코드 처리
                    .build();
                    
            GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
            List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();
            
            if (!records.isEmpty()) {
                logger.info("🔄 CONSUMER: {}개의 Kinesis 레코드 수신됨", records.size());
                
                // 각 레코드 처리
                for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
                    processRecord(record);
                }
                
                logger.info("✅ CONSUMER: {}개의 레코드 처리 완료", records.size());
            }
            
            // 다음 폴링을 위해 이터레이터 업데이트
            this.shardIterator = getRecordsResponse.nextShardIterator();
            
            // 🔧 샤드가 닫혔거나 이터레이터가 만료된 경우 재초기화
            if (shardIterator == null) {
                logger.warn("⚠️ CONSUMER: 샤드 이터레이터가 null입니다. 재초기화를 시도합니다.");
                // 여기서 재초기화 로직을 추가할 수 있습니다.
            }
            
        } catch (Exception e) {
            logger.error("❌ CONSUMER: 레코드 폴링 중 오류 발생", e);
            
            // 🔧 오류 발생 시 잠시 대기 후 재시도
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                isRunning = false;
            }
        }
    }

    /**
     * 🎯 [핵심] 개별 Kinesis 레코드를 처리합니다
     */
    private void processRecord(software.amazon.awssdk.services.kinesis.model.Record record) {
        try {
            // 레코드 데이터를 문자열로 변환
            String data = StandardCharsets.UTF_8.decode(record.data().asByteBuffer()).toString();
            
            logger.info("🎯 CONSUMER: 레코드 처리 시작 | 시퀀스번호: {} | 데이터: {}", 
                       record.sequenceNumber(), data);
            
            // JSON 파싱
            JsonNode eventNode = objectMapper.readTree(data);
            String eventType = eventNode.path("action").asText();
            
            if ("ADMIT".equals(eventType)) {
                String requestId = eventNode.path("requestId").asText();
                String movieId = eventNode.path("movieId").asText();
                String sessionId = eventNode.path("sessionId").asText();
                long timestamp = eventNode.path("timestamp").asLong();
                
                logger.info("🎉 CONSUMER: 입장 허가 이벤트 처리 시작 | requestId: {}... | movieId: {} | sessionId: {}...", 
                           requestId.substring(0, Math.min(8, requestId.length())), 
                           movieId, 
                           sessionId.substring(0, Math.min(8, sessionId.length())));
                
                // 🎯 핵심: WebSocket을 통해 해당 사용자에게 입장 허가 알림 전송
                webSocketService.notifyAdmission(requestId, movieId);
                
                logger.info("✅ CONSUMER: 입장 허가 WebSocket 알림 전송 완료 | requestId: {}... | movieId: {}", 
                           requestId.substring(0, Math.min(8, requestId.length())), movieId);
                
            } else {
                logger.warn("⚠️ CONSUMER: 알 수 없는 이벤트 타입: {} | 데이터: {}", eventType, data);
            }
            
        } catch (Exception e) {
            logger.error("❌ CONSUMER: 입장 이벤트 처리 실패 | 시퀀스번호: {} | 에러: {}", 
                        record.sequenceNumber(), e.getMessage(), e);
        }
    }

    /**
     * 📊 컨슈머 상태 정보 조회
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isEnabled() {
        return consumerEnabled;
    }
    
    public String getStreamName() {
        return streamName;
    }
    
    public String getCurrentShardIterator() {
        return shardIterator;
    }

    @PreDestroy
    public void shutdown() {
        logger.info("🛑 CONSUMER: Kinesis 컨슈머 종료 시작...");
        
        this.isRunning = false;
        
        if (consumerExecutor != null) {
            consumerExecutor.shutdown();
            try {
                if (!consumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warn("⚠️ CONSUMER: 정상 종료 시간 초과, 강제 종료 실행");
                    consumerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("⚠️ CONSUMER: 종료 대기 중 인터럽트 발생, 강제 종료 실행");
                consumerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (kinesisClient != null) {
            try {
                kinesisClient.close();
                logger.info("✅ CONSUMER: Kinesis 클라이언트 종료 완료");
            } catch (Exception e) {
                logger.error("❌ CONSUMER: Kinesis 클라이언트 종료 중 오류", e);
            }
        }
        
        logger.info("✅ CONSUMER: Kinesis 컨슈머 종료 완료");
    }
}