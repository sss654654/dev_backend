package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.couponmanagement.ws.LiveUpdatePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;
    private final AdmissionService admissionService;
    private final LiveUpdatePublisher liveUpdatePublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    public NotificationConsumer(KinesisClient kinesisClient, SimpMessagingTemplate messagingTemplate, AdmissionService admissionService,LiveUpdatePublisher liveUpdatePublisher) {
        this.kinesisClient = kinesisClient;
        this.messagingTemplate = messagingTemplate;
        this.admissionService = admissionService;
        this.liveUpdatePublisher = liveUpdatePublisher;
    }

    @PostConstruct
    public void startConsuming() {
        Thread consumerThread = new Thread(() -> {
            try {
                List<Shard> shards = kinesisClient.listShards(b -> b.streamName(streamName)).shards();
                String shardId = shards.get(0).shardId();
                final AtomicReference<String> shardIteratorRef = new AtomicReference<>(
                    kinesisClient.getShardIterator(b -> b.streamName(streamName)
                        .shardId(shardId).shardIteratorType(ShardIteratorType.LATEST)).shardIterator()
                );

                while (true) {
                    GetRecordsResponse response = kinesisClient.getRecords(b -> b.shardIterator(shardIteratorRef.get()));
                    
                    // 🔥 Record 앞에 전체 경로(패키지명)를 붙여서 명확하게 지정
                    for (software.amazon.awssdk.services.kinesis.model.Record record : response.records()) {
                        String data = record.data().asUtf8String();
                        logger.info("CONSUMER: Kinesis의 입장 처리 명령 수신 -> {}", data);
                        
                        try {
                            Map<String, String> message = objectMapper.readValue(data, Map.class);
                            String action = message.get("action");
                            String sessionId = message.get("sessionId");

                            if ("ADMIT".equals(action) && sessionId != null) {
                                admissionService.addToActiveSessions(sessionId);
                                logger.info("CONSUMER: {}님을 Redis 활성 세션에 등록했습니다.", sessionId);

                                liveUpdatePublisher.notifyAdmitted(sessionId);
                                //liveUpdatePublisher.broadcastStats();
                            }
                        } catch (Exception e) {
                            logger.error("Kinesis 메시지 처리 또는 WebSocket 전송 실패", e);
                        }
                    }
                    
                    shardIteratorRef.set(response.nextShardIterator());
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                logger.error("Kinesis consumer 에러 발생", e);
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
}