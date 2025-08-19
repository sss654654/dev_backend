package com.example.admission;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 변경점 1: application.yml에서 Kinesis 스트림 이름을 주입받도록 설정
    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    // 변경점 2: AdmissionService 의존성 제거. Consumer는 알림 역할에만 집중.
    public NotificationConsumer(KinesisClient kinesisClient, SimpMessagingTemplate messagingTemplate) {
        this.kinesisClient = kinesisClient;
        this.messagingTemplate = messagingTemplate;
    }

    private void handleRecord(String data) {
        try {
            // 변경점 3: 단순화된 메시지 형식에 맞게 파싱
            Map<String, String> message = objectMapper.readValue(data, Map.class);
            String action = message.get("action");
            String sessionId = message.get("sessionId");
            String movieId = message.get("movieId");

            if ("ADMIT".equals(action) && sessionId != null && movieId != null) {
                logger.info("CONSUMER: Kinesis 이벤트 수신 [MovieID: {}] -> 사용자 {}", movieId, sessionId);

                // 변경점 4: Redis 상태 변경 로직 제거.
                // 활성 세션 등록은 Producer(QueueProcessor)의 책임.

                String destination = "/topic/admit/" + sessionId;
                Map<String, String> payload = Map.of(
                    "status", "ADMITTED",
                    "message", "입장이 허가되었습니다. 예매를 진행해주세요.",
                    "movieId", movieId
                );
                
                messagingTemplate.convertAndSend(destination, payload);
                logger.info("==> WebSocket to {}: 입장 알림 전송 완료 <==", destination);
            }
        } catch (Exception e) {
            logger.error("Kinesis 메시지 처리 또는 WebSocket 전송 실패", e);
        }
    }

    @PostConstruct
    public void startConsuming() {
        // 이 부분은 Kinesis에서 데이터를 읽어오는 로우레벨 코드이므로 변경할 필요가 없습니다.
        // handleRecord 메서드의 로직이 중요합니다.
        Thread consumerThread = new Thread(() -> {
            try {
                // 스트림이 생성될 때까지 잠시 대기 (LocalStack 초기화 시간 고려)
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