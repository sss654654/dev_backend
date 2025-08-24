package com.example.admission.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL) // null인 필드는 JSON에서 제외
public class EnterResponse {

    public enum Status { SUCCESS, QUEUED, FAILED }

    private final Status status;
    private final String message;
    private final String requestId;
    private final Long myRank;
    private final Long totalWaiting;

    public EnterResponse(Status status, String message, String requestId, Long myRank, Long totalWaiting) {
        this.status = status;
        this.message = message;
        this.requestId = requestId;
        this.myRank = myRank;
        this.totalWaiting = totalWaiting;
    }
    
    // [오류 수정] 누락되었던 Getter 메소드들을 추가합니다.
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