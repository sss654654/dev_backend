// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QueueProcessor {
    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);
    
    private final AdmissionService admissionService;
    private final LoadBalancingOptimizer loadBalancingOptimizer;
    private final KinesisAdmissionProducer kinesisProducer;

    public QueueProcessor(AdmissionService admissionService, 
                          LoadBalancingOptimizer loadBalancingOptimizer,
                          KinesisAdmissionProducer kinesisProducer) {
        this.admissionService = admissionService;
        this.loadBalancingOptimizer = loadBalancingOptimizer;
        this.kinesisProducer = kinesisProducer;
    }

    @Scheduled(fixedRateString = "${queueProcessorInterval:1000}")
    public void processAdmissionQueues() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds.isEmpty()) return;
            
            for (String movieId : movieIds) {
                if (loadBalancingOptimizer.shouldProcessMovie(movieId)) {
                    processMovieQueue("movie", movieId);
                }
            }
        } catch (Exception e) {
            logger.error("❌ 대기열 처리 중 전체 오류 발생", e);
        }
    }

    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);
            
            boolean admitted = false;
            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots, waitingCount);
                List<String> admittedUsers = admissionService.admitNextUsers(type, movieId, admitCount);
                
                if (!admittedUsers.isEmpty()) {
                    admitted = true;
                    kinesisProducer.publishAdmitEvents(admittedUsers, movieId);
                }
            }
            
            if (admitted || waitingCount > 0) {
                long currentTotalWaiting = admissionService.getTotalWaitingCount(type, movieId);
                Map<String, Long> allRanks = admissionService.getAllUserRanks(type, movieId);
                kinesisProducer.publishRankUpdateEvents(movieId, currentTotalWaiting, allRanks);
            }

        } catch (Exception e) {
            logger.error("❌ [{}] 영화 대기열 처리 중 오류", movieId, e);
        }
    }
}