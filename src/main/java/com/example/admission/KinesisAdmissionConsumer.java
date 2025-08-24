package com.example.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.kinesis.model.Record;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kinesis Consumer - Kinesis 스트림에서 입장 허가 이벤트를 받아서 WebSocket으로 전송
 */
@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);
    
    private final KinesisClient kinesisClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public KinesisAdmissionConsumer(KinesisClient kinesisClient, SimpMessagingTemplate messagingTemplate) {
        this.kinesisClient = kinesisClient;
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void startConsumer() {
        if (running.compareAndSet(false, true)) {
            consumerThread = new Thread(this::consumeFromKinesis, "KinesisConsumer");
            consumerThread.setDaemon(true);
            consumerThread.start();
            logger.info("CONSUMER: Kinesis Consumer 시작됨 - 스트림: {}", streamName);
        }
    }

    @PreDestroy
    public void stopConsumer() {
        running.set(false);
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000); // 5초 대기
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.info("CONSUMER: Kinesis Consumer 종료됨");
    }

    /**
     * Kinesis 스트림에서 계속 레코드를 읽어오는 메인 루프
     */
    private void consumeFromKinesis() {
        String shardIterator = null;
        
        try {
            // 1. 스트림 정보 확인
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();
            
            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            List<software.amazon.awssdk.services.kinesis.model.Shard> shards = describeResponse.streamDescription().shards();
            
            if (shards.isEmpty()) {
                logger.warn("CONSUMER: 스트림에 샤드가 없습니다: {}", streamName);
                return;
            }
            
            // 2. 첫 번째 샤드의 iterator 가져오기 (LATEST로 시작)
            String shardId = shards.get(0).shardId();
            GetShardIteratorRequest iteratorRequest = GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .shardIteratorType(ShardIteratorType.LATEST)
                .build();
            
            GetShardIteratorResponse iteratorResponse = kinesisClient.getShardIterator(iteratorRequest);
            shardIterator = iteratorResponse.shardIterator();
            
            logger.info("CONSUMER: 샤드 Iterator 초기화 완료 - shardId: {}", shardId);
            
            // 3. 메인 소비 루프
            while (running.get() && shardIterator != null) {
                try {
                    GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                        .shardIterator(shardIterator)
                        .limit(10) // 한 번에 최대 10개 레코드
                        .build();
                    
                    GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
                    List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();
                    
                    // 레코드가 있으면 처리
                    if (!records.isEmpty()) {
                        processKinesisRecords(records);
                    }
                    
                    // 다음 iterator 업데이트
                    shardIterator = getRecordsResponse.nextShardIterator();
                    
                    // 레코드가 없으면 잠시 대기
                    if (records.isEmpty()) {
                        Thread.sleep(1000); // 1초 대기
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("CONSUMER: Consumer 인터럽트됨");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("CONSUMER: 레코드 처리 중 오류", e);
                    Thread.sleep(5000); // 오류 시 5초 대기 후 재시도
                }
            }
            
        } catch (Exception e) {
            logger.error("CONSUMER: Kinesis Consumer 초기화 실패", e);
        }
    }

    /**
     * Kinesis 레코드 리스트를 처리
     */
    private void processKinesisRecords(List<software.amazon.awssdk.services.kinesis.model.Record> records) {
        for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
            String data = record.data().asUtf8String();
            handleRecord(data);
        }
    }

    /**
     * 개별 레코드 처리 - ADMIT 이벤트를 WebSocket으로 전송
     */
    private void handleRecord(String data) {
        logger.info("CONSUMER: Kinesis 레코드 수신 - {}", data);
        
        try {
            Map<String, Object> message = objectMapper.readValue(data, new TypeReference<>() {});
            String action = (String) message.get("action");

            if ("ADMIT".equals(action)) {
                String requestId = (String) message.get("requestId");
                String movieId = (String) message.get("movieId");
                String sessionId = (String) message.get("sessionId");

                if (requestId == null) {
                    logger.warn("CONSUMER: requestId가 누락된 메시지: {}", data);
                    return;
                }

                logger.info("CONSUMER: ADMIT 이벤트 처리 - requestId: {}, movieId: {}", requestId, movieId);
                
                // WebSocket으로 사용자에게 입장 허가 알림 전송
                sendAdmissionNotification(requestId, movieId, sessionId);
                
            } else {
                logger.debug("CONSUMER: 알 수 없는 액션: {}", action);
            }
            
        } catch (Exception e) {
            logger.error("CONSUMER: 레코드 처리 실패 - data: {}", data, e);
        }
    }

    /**
     * WebSocket을 통해 사용자에게 입장 허가 알림 전송
     */
    private void sendAdmissionNotification(String requestId, String movieId, String sessionId) {
        try {
            String destination = "/topic/admit/" + requestId;
            Map<String, Object> payload = Map.of(
                "status", "ADMITTED",
                "message", "입장이 허가되었습니다. 좌석을 선택해주세요.",
                "movieId", movieId,
                "sessionId", sessionId,
                "timestamp", System.currentTimeMillis()
            );

            messagingTemplate.convertAndSend(destination, payload);
            logger.info("CONSUMER: WebSocket 알림 전송 완료 - destination: {}", destination);
            
        } catch (Exception e) {
            logger.error("CONSUMER: WebSocket 전송 실패 - requestId: {}", requestId, e);
        }
    }
}