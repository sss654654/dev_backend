package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.ws.LiveUpdatePublisher;
import com.fasterxml.jackson.core.type.TypeReference;
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

    public NotificationConsumer(KinesisClient kinesisClient, SimpMessagingTemplate messagingTemplate, AdmissionService admissionService, LiveUpdatePublisher liveUpdatePublisher) {
        this.kinesisClient = kinesisClient;
        this.messagingTemplate = messagingTemplate;
        this.admissionService = admissionService;
        this.liveUpdatePublisher = liveUpdatePublisher;
    }

    private void handleRecord(String data) {
        logger.info("CONSUMER RAW DATA RECEIVED FROM KINESIS: {}", data);
        try {
            Map<String, Object> message = objectMapper.readValue(data, new TypeReference<>() {});
            String action = (String) message.get("action");

            if ("ADMIT".equals(action)) {
                String type = (String) message.get("type");
                String id = (String) message.get("id");
                String requestId = (String) message.get("requestId");
                long admittedSeq = ((Number) message.get("admittedSeq")).longValue();

                if (requestId == null) {
                    logger.warn("Kinesis 메시지에 requestId가 없습니다: {}", data);
                    return;
                }

                logger.info("CONSUMER: Kinesis 이벤트 수신 [{}:{}] -> 사용자 requestId {}", type, id, requestId);

                String destination = "/topic/admit/" + requestId;
                Map<String, Object> payload = Map.of(
                        "status", "ADMITTED",
                        "message", "입장이 허가되었습니다. 예매를 진행해주세요.",
                        "type", type,
                        "id", id
                );
                messagingTemplate.convertAndSend(destination, payload);
                logger.info("==> WebSocket to {}: 개인 입장 알림 전송 완료", destination);

                long totalWaiting = admissionService.getTotalWaitingCount(type, id);
                liveUpdatePublisher.broadcastStats(type, id, admittedSeq, totalWaiting);
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

                // ★★★★★ 핵심 수정 부분 ★★★★★
                final AtomicReference<String> shardIteratorRef = new AtomicReference<>(
                        kinesisClient.getShardIterator(b -> b.streamName(streamName)
                                        .shardId(shardId)
                                        // LATEST에서 TRIM_HORIZON으로 변경하여 스트림의 처음부터 모든 데이터를 읽도록 설정
                                        .shardIteratorType(ShardIteratorType.TRIM_HORIZON))
                                .shardIterator()
                );
                // ★★★★★ 여기까지 수정 ★★★★★

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
                // 스레드 실행 중 발생하는 예외를 더 상세하게 로깅
                logger.error("Kinesis consumer 스레드 실행 중 심각한 오류 발생", e);
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
}
