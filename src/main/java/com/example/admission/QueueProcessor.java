package com.example.admission;

import com.example.admission.service.AdmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kinesis.KinesisClient;
// ... Kinesis 관련 import ...

import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    private final AdmissionService admissionService;
    private final KinesisClient kinesisClient; // Kinesis 클라이언트 주입

    public QueueProcessor(AdmissionService admissionService, KinesisClient kinesisClient) {
        this.admissionService = admissionService;
        this.kinesisClient = kinesisClient;
    }

    @Scheduled(fixedRate = 2000) // 2초마다 실행
    public void process() {
        // 1. 현재 활성 세션 수를 확인
        long activeCount = admissionService.getActiveSessionCount();
        
        // 2. 최대 세션 수 대비 빈자리를 계산
        long vacantSlots = 50 - activeCount; // 50은 AdmissionService의 MAX_SESSIONS와 일치해야 함

        if (vacantSlots > 0) {
            // 3. 빈자리만큼 대기열에서 사용자를 꺼내옴
            Set<String> admittedUsers = admissionService.popNextUsersFromQueue(vacantSlots);

            if (!admittedUsers.isEmpty()) {
                logger.info("{} 명의 사용자를 대기열에서 입장시킵니다: {}", admittedUsers.size(), admittedUsers);
                
                // 4. 입장 처리된 사용자들을 Kinesis로 발행
                for (String user : admittedUsers) {
                    // 여기에 Kinesis PutRecord 로직 구현
                    // kinesisClient.putRecord(...);
                    logger.info("KINESIS PRODUCER: {}님 입장 이벤트를 발행했습니다.", user);
                }
            }
        }
    }
}