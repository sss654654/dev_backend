// src/main/java/com/example/admission/dto/EnterResponse.java
package com.example.admission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnterResponse {

    public enum Status {
        SUCCESS,
        QUEUED,
        ERROR
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
    
    // Getters
    public Status getStatus() { return status; }
    public String getMessage() { return message; }
    public String getRequestId() { return requestId; }
    public Long getMyRank() { return myRank; }
    public Long getTotalWaiting() { return totalWaiting; }
}