package com.example.admission;

import com.example.admission.ws.WebSocketUpdateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.GetRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.GetRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.kinesis.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.kinesis.model.Shard;
import software.amazon.awssdk.services.kinesis.model.ShardIteratorType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 🔹 개선된 Kinesis Consumer - 상세한 로깅과 안정성 강화
 */
@Component
public class KinesisAdmissionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KinesisAdmissionConsumer.class);

    private final KinesisClient kinesisClient;
    private final WebSocketUpdateService webSocketUpdateService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread consumerThread;

    // 🔹 통계 추적을 위한 카운터들
    private final AtomicLong totalProcessedRecords = new AtomicLong(0);
    private final AtomicLong totalAdmitEvents = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    @Value("${admission.kinesis-consumer-enabled:true}")
    private boolean consumerEnabled;

    public KinesisAdmissionConsumer(KinesisClient kinesisClient, WebSocketUpdateService webSocketUpdateService) {
        this.kinesisClient = kinesisClient;
        this.webSocketUpdateService = webSocketUpdateService;
    }

    @PostConstruct
    public void startConsumer() {
        if (!consumerEnabled) {
            logger.warn("🚫 CONSUMER: Kinesis Consumer 비활성화됨 (설정: kinesis-consumer-enabled=false)");
            return;
        }

        if (running.compareAndSet(false, true)) {
            consumerThread = new Thread(this::consumeFromKinesis, "KinesisConsumerThread");
            consumerThread.setDaemon(true);
            consumerThread.start();
            logger.info("🚀 CONSUMER: Kinesis Consumer 시작됨 - 스트림: {}", streamName);
        }
    }

    @PreDestroy
    public void stopConsumer() {
        logger.info("⏹️ CONSUMER: 종료 신호 수신, Consumer 중지 중...");
        running.set(false);
        
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(5000); // 5초 대기
                logger.info("✅ CONSUMER: Consumer 스레드 정상 종료됨");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("⚠️ CONSUMER: 종료 대기 중 인터럽트됨");
            }
        }

        // 최종 통계 로깅
        logger.info("📊 CONSUMER: 최종 통계 - 처리된 레코드: {}, ADMIT 이벤트: {}, 오류: {}", 
                   totalProcessedRecords.get(), totalAdmitEvents.get(), totalErrors.get());
    }

    /**
     * 🔹 Kinesis 스트림에서 계속 레코드를 읽어오는 메인 루프 (개선된 로깅)
     */
    private void consumeFromKinesis() {
        String shardIterator = null;
        int emptyBatchCount = 0;

        try {
            // 1. 스트림 정보 확인
            logger.info("🔍 CONSUMER: 스트림 정보 조회 중... - {}", streamName);
            DescribeStreamRequest describeRequest = DescribeStreamRequest.builder()
                .streamName(streamName)
                .build();

            DescribeStreamResponse describeResponse = kinesisClient.describeStream(describeRequest);
            List<Shard> shards = describeResponse.streamDescription().shards();

            if (shards.isEmpty()) {
                logger.error("❌ CONSUMER: 스트림에 샤드가 없습니다: {}", streamName);
                return;
            }

            logger.info("✅ CONSUMER: 스트림 정보 확인 완료 - 샤드 수: {}", shards.size());

            // 2. 첫 번째 샤드의 iterator 가져오기 (LATEST로 시작)
            String shardId = shards.get(0).shardId();
            GetShardIteratorRequest iteratorRequest = GetShardIteratorRequest.builder()
                .streamName(streamName)
                .shardId(shardId)
                .shardIteratorType(ShardIteratorType.LATEST)
                .build();

            GetShardIteratorResponse iteratorResponse = kinesisClient.getShardIterator(iteratorRequest);
            shardIterator = iteratorResponse.shardIterator();

            logger.info("🎯 CONSUMER: 샤드 Iterator 초기화 완료 - shardId: {}", shardId);
            logger.info("🔄 CONSUMER: 레코드 소비 루프 시작...");

            // 3. 메인 소비 루프
            while (running.get() && shardIterator != null) {
                try {
                    GetRecordsRequest getRecordsRequest = GetRecordsRequest.builder()
                        .shardIterator(shardIterator)
                        .limit(10) // 한 번에 최대 10개 레코드
                        .build();

                    GetRecordsResponse getRecordsResponse = kinesisClient.getRecords(getRecordsRequest);
                    List<software.amazon.awssdk.services.kinesis.model.Record> records = getRecordsResponse.records();

                    // 🔹 레코드가 있으면 처리
                    if (!records.isEmpty()) {
                        emptyBatchCount = 0; // 리셋
                        logger.debug("📥 CONSUMER: {} 개 레코드 수신됨", records.size());
                        processKinesisRecords(records);
                    } else {
                        emptyBatchCount++;
                        if (emptyBatchCount % 60 == 0) { // 1분마다 한 번 로깅 (5초*12 = 1분)
                            logger.debug("⏳ CONSUMER: 대기 중... ({}분째 레코드 없음)", emptyBatchCount / 12);
                        }
                    }

                    // 다음 iterator 업데이트
                    shardIterator = getRecordsResponse.nextShardIterator();

                    // 레코드가 없으면 잠시 대기
                    if (records.isEmpty()) {
                        Thread.sleep(5000); // 5초 대기
                    }

                } catch (InterruptedException e) {
                    logger.info("🛑 CONSUMER: Consumer 인터럽트됨, 정상 종료 중...");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    totalErrors.incrementAndGet();
                    logger.error("❌ CONSUMER: 레코드 처리 중 오류, 5초 후 재시도", e);
                    Thread.sleep(5000); // 오류 시 5초 대기 후 재시도
                }
            }

        } catch (Exception e) {
            logger.error("💥 CONSUMER: Kinesis Consumer 초기화 실패", e);
        }
    }

    /**
     * 🔹 Kinesis 레코드 리스트를 처리 (배치 처리 로깅 추가)
     */
    private void processKinesisRecords(List<software.amazon.awssdk.services.kinesis.model.Record> records) {
        long batchStartTime = System.currentTimeMillis();
        int processedCount = 0;
        int admitEventCount = 0;
        
        for (software.amazon.awssdk.services.kinesis.model.Record record : records) {
            try {
                String data = record.data().asUtf8String();
                boolean wasAdmitEvent = handleRecord(data);
                processedCount++;
                if (wasAdmitEvent) admitEventCount++;
                
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                logger.error("❌ CONSUMER: 개별 레코드 처리 실패", e);
            }
        }
        
        totalProcessedRecords.addAndGet(processedCount);
        totalAdmitEvents.addAndGet(admitEventCount);
        
        long batchDuration = System.currentTimeMillis() - batchStartTime;
        
        // 🚨 배치 처리 결과 로깅
        logger.info("✅ CONSUMER: 배치 처리 완료 | 처리: {} | ADMIT: {} | 소요시간: {}ms", 
                   processedCount, admitEventCount, batchDuration);
    }

    /**
     * 🔹 개별 레코드 처리 - ADMIT 이벤트를 WebSocket으로 전송 (상세 로깅)
     * @return ADMIT 이벤트였는지 여부
     */
    private boolean handleRecord(String data) {
        logger.debug("📨 CONSUMER: 레코드 수신 - 크기: {} bytes", data.length());

        try {
            Map<String, Object> message = objectMapper.readValue(data, new TypeReference<>() {});
            String action = (String) message.get("action");
            String requestId = (String) message.get("requestId");
            String movieId = (String) message.get("movieId");
            Long timestamp = (Long) message.get("timestamp");

            // 🔹 메시지 상세 정보 로깅
            logger.debug("🔍 CONSUMER: 메시지 파싱 완료 - action: {}, requestId: {}, movieId: {}", 
                        action, requestId, movieId);

            if ("ADMIT".equals(action)) {
                if (requestId == null) {
                    logger.warn("⚠️ CONSUMER: requestId가 누락된 ADMIT 메시지: {}", data);
                    return false;
                }

                // 🚨 핵심: WebSocket으로 입장 알림 전송
                logger.info("🎬 CONSUMER: ADMIT 이벤트 처리 시작 - requestId: {}, movieId: {}", 
                           requestId, movieId);

                webSocketUpdateService.notifyAdmitted(requestId);
                
                logger.info("✅ CONSUMER: WebSocket 입장 알림 전송 완료 - requestId: {}", requestId);
                return true;

            } else if ("HEALTH_CHECK".equals(action)) {
                logger.debug("💗 CONSUMER: Health check 메시지 수신");
                return false;
            } else {
                logger.debug("❓ CONSUMER: 알 수 없는 액션 - action: {}, 메시지 무시", action);
                return false;
            }

        } catch (Exception e) {
            logger.error("💥 CONSUMER: 레코드 처리 실패 - data: {}", data, e);
            return false;
        }
    }

    /**
     * 🔹 Consumer 통계 조회 (모니터링용)
     */
    public Map<String, Object> getConsumerStats() {
        return Map.of(
            "running", running.get(),
            "totalProcessedRecords", totalProcessedRecords.get(),
            "totalAdmitEvents", totalAdmitEvents.get(),
            "totalErrors", totalErrors.get(),
            "streamName", streamName,
            "consumerEnabled", consumerEnabled
        );
    }
}