package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    private final AdmissionService admissionService;
    private final KinesisClient kinesisClient;

    @Value("${admission.kinesis-stream-name}")
    private String streamName;

    // 처리해야 할 영화 ID 목록을 스케줄러가 직접 관리합니다.
    private static final List<String> MOVIE_IDS = List.of("1", "2", "3", "4");

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
    }

    @Scheduled(fixedRate = 2000)
    public void processQueues() {
        logger.trace("대기열 처리 스케줄러 실행...");

        // 1. 고정된 영화 ID 목록을 순회하며 영화 대기열을 처리합니다.
        for (String movieId : MOVIE_IDS) {
            processSingleQueue("movie", movieId);
        }

        // 2. ★★★ 쿠폰 대기열도 동일한 로직으로 처리합니다. ★★★
        processSingleQueue("coupon", "global");
    }

    private void processSingleQueue(String type, String id) {
        // 3. 현재 빈자리 수를 계산합니다.
        long vacantSlots = admissionService.getVacantSlots(type, id);
        if (vacantSlots <= 0) {
            return;
        }

        // 4. 빈자리 수만큼 사용자들을 '배치'로 한번에 꺼내옵니다.
        Set<ZSetOperations.TypedTuple<String>> admittedUsers = admissionService.admitNextUsers(type, id, vacantSlots);

        // ★ 디버깅 로그 1: Redis에서 몇 명을 가져왔는지 확인
        logger.info("DEBUG [{}:{}] Redis에서 {}명의 입장 후보자 확인.", type, id, admittedUsers != null ? admittedUsers.size() : 0);

        if (admittedUsers == null || admittedUsers.isEmpty()) {
            return;
        }

        List<PutRecordsRequestEntry> kinesisRecords = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> user : admittedUsers) {
            String requestId = user.getValue();
            long admittedSeq = user.getScore() != null ? user.getScore().longValue() : 0L;

            // ★ 디버깅 로그 2: Kinesis로 보낼 데이터가 정상적으로 생성되는지 확인
            if (requestId == null) {
                logger.error("DEBUG [{}:{}] requestId가 null입니다! user: {}", type, id, user);
                continue; // requestId가 null이면 이 레코드는 건너뜁니다.
            }
            logger.info("DEBUG [{}:{}] Kinesis 레코드 생성 중: requestId={}, seq={}", type, id, requestId, admittedSeq);

            String eventData = String.format(
                    "{\"action\":\"ADMIT\", \"type\":\"%s\", \"id\":\"%s\", \"requestId\":\"%s\", \"admittedSeq\":%d}",
                    type, id, requestId, admittedSeq
            );

            kinesisRecords.add(PutRecordsRequestEntry.builder()
                    .partitionKey(requestId)
                    .data(SdkBytes.fromString(eventData, StandardCharsets.UTF_8))
                    .build());
        }

        if (!kinesisRecords.isEmpty()) {
            try { // ★ 디버깅 로그 3: Kinesis 전송 직전에 시도함을 알림
                logger.info("DEBUG [{}:{}] Kinesis로 {}개 레코드 전송 시도 직전...", type, id, kinesisRecords.size());

                PutRecordsRequest recordsRequest = PutRecordsRequest.builder()
                        .streamName(streamName)
                        .records(kinesisRecords)
                        .build();

                kinesisClient.putRecords(recordsRequest);

                // 이 로그가 보이면 Kinesis 전송은 성공한 것입니다.
                logger.info("PRODUCER [{}:{}] {}개의 입장 이벤트를 Kinesis로 발행했습니다.", type, id, kinesisRecords.size());

            } catch (Exception e) {
                // ★★★ Kinesis 전송 실패 시, 여기서 에러 로그가 발생합니다! ★★★
                logger.error("FATAL: Kinesis putRecords API 호출 실패!", e);
            }
        }
    }
}