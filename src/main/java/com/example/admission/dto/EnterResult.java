package com.example.admission.dto;


/**
 * 대기열 진입 결과를 담는 객체.
 * QUEUED 상태일 때 클라이언트에게 필요한 상세 정보를 제공합니다.
 */

public class EnterResult {
    private final Status status;
    private final Long myRank;       // 나의 현재 대기 순위 (예: 500등)
    private final Long mySeq;        // 내가 발급받은 고유 순번 (예: 1500번)
    private final Long headSeq;      // 현재 처리 중인 순번 (예: 1000번)
    private final Long totalWaiting; // 총 대기자 수

    private EnterResult(Status status, Long myRank, Long mySeq, Long headSeq, Long totalWaiting) {
        this.status = status;
        this.myRank = myRank;
        this.mySeq = mySeq;
        this.headSeq = headSeq;
        this.totalWaiting = totalWaiting;
    }

    public static EnterResult success() {
        return new EnterResult(Status.SUCCESS, null, null, null, null);
    }

    public static EnterResult queued(Long myRank, Long mySeq, Long headSeq, Long totalWaiting) {
        return new EnterResult(Status.QUEUED, myRank, mySeq, headSeq, totalWaiting);
    }

    public enum Status {
        SUCCESS, QUEUED
    }

    public Long getHeadSeq() {
        return headSeq;
    }

    public Long getMyRank() {
        return myRank;
    }

    public Long getMySeq() {
        return mySeq;
    }

    public Long getTotalWaiting() {
        return totalWaiting;
    }

    public Status getStatus() {
        return status;
    }

}

