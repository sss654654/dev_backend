package com.example.admission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnterResponse {

    public enum Status { 
        SUCCESS, 
        QUEUED, 
        ERROR  // ERROR 상태 추가
    }

    private final Status status;
    private final String message;
    private final String requestId;
    private final Long myRank;
    private final Long totalWaiting;

    // 생성자
    public EnterResponse(Status status, String message, String requestId, Long myRank, Long totalWaiting) {
        this.status = status;
        this.message = message;
        this.requestId = requestId;
        this.myRank = myRank;
        this.totalWaiting = totalWaiting;
    }
    
    // Getter 메서드들
    public Status status() {
        return status;
    }

    public String message() {
        return message;
    }

    public String requestId() {
        return requestId;
    }

    public Long myRank() {
        return myRank;
    }

    public Long totalWaiting() {
        return totalWaiting;
    }

    // Jackson을 위한 일반적인 getter들도 제공
    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getMyRank() {
        return myRank;
    }

    public Long getTotalWaiting() {
        return totalWaiting;
    }
}