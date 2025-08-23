package com.example.admission.dto;

import java.util.List;

/**
 * 대기열 시스템 메트릭 정보를 담는 DTO
 */
public record AdmissionMetrics(
    long timestamp,
    int podCount,
    long maxSessions,
    long currentActiveSessions,
    long currentWaitingUsers,
    long totalEntriesProcessed,
    long totalTimeouts,
    long totalQueueJoins,
    long totalBatchProcesses,
    long avgProcessingTimeMs,
    List<Long> throughputHistory,      // 분당 처리량 히스토리 (최근 100분)
    List<Long> queueSizeHistory,       // 대기열 크기 히스토리 (최근 100회)
    List<Long> podUtilizationHistory   // Pod 활용도 히스토리 (최근 100회)
) {
    
    /**
     * 현재 Pod 활용률 계산 (%)
     */
    public double getCurrentUtilizationRate() {
        return maxSessions > 0 ? (double) currentActiveSessions / maxSessions * 100 : 0.0;
    }
    
    /**
     * 평균 분당 처리량 계산
     */
    public double getAverageThroughputPerMinute() {
        return throughputHistory.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * 시스템 상태 평가
     */
    public String getSystemStatus() {
        double utilization = getCurrentUtilizationRate();
        
        if (utilization >= 90) return "OVERLOADED";
        if (utilization >= 70) return "BUSY";  
        if (utilization >= 30) return "NORMAL";
        if (utilization > 0) return "LIGHT";
        return "IDLE";
    }
    
    /**
     * 대기열 증가 추세 확인
     */
    public boolean isQueueGrowing() {
        if (queueSizeHistory.size() < 3) return false;
        
        int size = queueSizeHistory.size();
        long recent = queueSizeHistory.get(size - 1);
        long previous = queueSizeHistory.get(size - 2);
        long beforePrevious = queueSizeHistory.get(size - 3);
        
        return recent > previous && previous >= beforePrevious;
    }
}