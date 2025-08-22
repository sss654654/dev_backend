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

    public NotificationConsumer(KinesisClient kinesisClient, 
                               SimpMessagingTemplate messagingTemplate, 
                               AdmissionService admissionService, 
                               LiveUpdatePublisher liveUpdatePublisher) {
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
                        "admittedSeq", admittedSeq
                );

                messagingTemplate.convertAndSend(destination, payload);
                logger.info("CONSUMER: WebSocket 메시지 전송 완료 -> {}", destination);
            }
        } catch (Exception e) {
            logger.error("Kinesis 메시지 처리 중 오류 발생: {}", data, e);
        }
    }

    // ✅ Record 타입을 명시적으로 지정하여 충돌 해결
    private void processKinesisRecords(List<software.amazon.awssdk.services.kinesis.model.Record> records) {
        for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
            String data = record.data().asUtf8String();
            handleRecord(data);
        }
    }
}