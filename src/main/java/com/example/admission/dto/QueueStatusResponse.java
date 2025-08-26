package com.example.admission.dto;

/**
 * 대기열 상태 조회 응답을 위한 DTO
 */
public record QueueStatusResponse(
    Status status,
    String message,
    Long myRank,        // 현재 대기 순위
    Long totalWaiting,  // 총 대기자 수
    Long timestamp
) {
    
    public enum Status {
        WAITING,    // 대기열에서 대기 중
        ADMITTED,   // 활성 세션으로 승격됨
        NOT_FOUND   // 대기열/활성 세션 모두에 없음
    }
    
    public static QueueStatusResponse waiting(long myRank, long totalWaiting) {
        return new QueueStatusResponse(
            Status.WAITING,
            String.format("현재 %d번째 순서입니다. (전체 대기자: %d명)", myRank, totalWaiting),
            myRank,
            totalWaiting,
            System.currentTimeMillis()
        );
    }
    
    public static QueueStatusResponse admitted() {
        return new QueueStatusResponse(
            Status.ADMITTED,
            "입장이 승인되었습니다.",
            null,
            null,
            System.currentTimeMillis()
        );
    }
    
    public static QueueStatusResponse notFound() {
        return new QueueStatusResponse(
            Status.NOT_FOUND,
            "대기열에 등록되어 있지 않습니다.",
            null,
            null,
            System.currentTimeMillis()
        );
    }
}