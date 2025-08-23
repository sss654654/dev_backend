package com.example.admission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.Record; // ★ AWS Kinesis Record만 사용

import java.util.List;
import java.util.Map;

@Component
public class NotificationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationConsumer.class);
    private final KinesisClient kinesisClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    // ★ 생성자에서 AdmissionService와 LiveUpdatePublisher(WebSocketUpdateService) 의존성 제거
    public NotificationConsumer(KinesisClient kinesisClient, SimpMessagingTemplate messagingTemplate) {
        this.kinesisClient = kinesisClient;
        this.messagingTemplate = messagingTemplate;
    }

    // Kinesis에서 받은 레코드를 처리하는 private 메서드
    private void handleRecord(String data) {
        logger.info("CONSUMER RAW DATA RECEIVED FROM KINESIS: {}", data);
        try {
            Map<String, Object> message = objectMapper.readValue(data, new TypeReference<>() {});
            String action = (String) message.get("action");

            if ("ADMIT".equals(action)) {
                String requestId = (String) message.get("requestId");

                if (requestId == null) {
                    logger.warn("Kinesis 메시지에 requestId가 없습니다: {}", data);
                    return;
                }

                logger.info("CONSUMER: Kinesis 이벤트 수신 -> 사용자 requestId {}", requestId);
                
                // ★ 참고: 이 로직은 QueueProcessor가 직접 WebSocket을 보내는 것의 '백업' 또는 '이벤트 기록' 역할을 합니다.
                // 시스템 안정성을 위해 유지할 수 있습니다.
                String destination = "/topic/admit/" + requestId;
                Map<String, Object> payload = Map.of(
                    "status", "ADMITTED",
                    "message", "입장이 허가되었습니다. (From Kinesis)"
                );

                messagingTemplate.convertAndSend(destination, payload);
                logger.info("CONSUMER: WebSocket 메시지 전송 완료 -> {}", destination);
            }
        } catch (Exception e) {
            logger.error("Kinesis 메시지 처리 중 오류 발생: {}", data, e);
        }
    }

    // Kinesis 레코드 리스트를 처리하는 private 헬퍼 메서드
    private void processKinesisRecords(List<Record> records) {
        for (Record record : records) {
            String data = record.data().asUtf8String();
            handleRecord(data);
        }
    }
    
    // ★ 참고: Kinesis 스트림을 읽어오는 로직(예: @PostConstruct 스레드)은 
    //    이 클래스 내 다른 곳에 구현되어 있다고 가정합니다.
    //    만약 없다면 해당 로직도 추가해야 합니다.
}