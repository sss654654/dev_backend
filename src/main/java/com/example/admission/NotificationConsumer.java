package com.example.admission;

import com.example.websockets.LiveUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class NotificationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationConsumer.class);
    private final KinesisClient kinesisClient;
    private final LiveUpdateService liveUpdateService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public NotificationConsumer(KinesisClient kinesisClient, LiveUpdateService liveUpdateService) {
        this.kinesisClient = kinesisClient;
        this.liveUpdateService = liveUpdateService;
    }

    private void handleRecord(String data) {
        try {
            Map<String, String> message = objectMapper.readValue(data, Map.class);
            String action = message.get("action");
            String sessionId = message.get("sessionId");
            String movieId = message.get("movieId");
            String requestId = message.get("requestId"); // requestId 추출

             if ("ADMIT".equals(action) && sessionId != null && movieId != null && requestId != null) {
                // ▼▼▼▼▼▼▼▼▼▼ 이 부분 로그 수정 ▼▼▼▼▼▼▼▼▼▼
                logger.info("CONSUMER: Kinesis로부터 [{}:{}] 입장 이벤트를 수신했습니다. (requestId: {})", movieId, sessionId, requestId);
                // ▲▲▲▲▲▲▲▲▲▲ 여기까지 수정 ▲▲▲▲▲▲▲▲▲▲
                
                liveUpdateService.notifyAdmitted(requestId, movieId, sessionId);
            }
        } catch (Exception e) {
            logger.error("Kinesis 메시지 처리 또는 WebSocket 전송 실패", e);
        }
    }

    @PostConstruct
    public void startConsuming() {
        Thread consumerThread = new Thread(() -> {
            try {
                Thread.sleep(5000); 

                List<Shard> shards = kinesisClient.listShards(b -> b.streamName(streamName)).shards();
                String shardId = shards.get(0).shardId();
                final AtomicReference<String> shardIteratorRef = new AtomicReference<>(
                    kinesisClient.getShardIterator(b -> b.streamName(streamName)
                        .shardId(shardId).shardIteratorType(ShardIteratorType.LATEST)).shardIterator()
                );

                while (!Thread.currentThread().isInterrupted()) {
                    GetRecordsResponse response = kinesisClient.getRecords(b -> b.shardIterator(shardIteratorRef.get()));
                    
                    for (software.amazon.awssdk.services.kinesis.model.Record record : response.records()) {
                        String data = record.data().asUtf8String();
                        handleRecord(data);
                    }
                    
                    shardIteratorRef.set(response.nextShardIterator());
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                logger.info("Kinesis consumer 스레드가 중단되었습니다.");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("Kinesis consumer 에러 발생", e);
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
}