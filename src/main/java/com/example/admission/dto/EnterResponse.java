package com.example.admission.dto;

public class EnterResponse {

    private Status status;
    private String message;
    private String requestId;
    private String waitUrl;

    public enum Status {
        SUCCESS, QUEUED, FAILED
    }

    // 기본 생성자
    public EnterResponse() {}

    // 모든 필드를 받는 생성자
    public EnterResponse(Status status, String message, String requestId, String waitUrl) {
        this.status = status;
        this.message = message;
        this.requestId = requestId;
        this.waitUrl = waitUrl;
    }

    // Getters and Setters
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getWaitUrl() { return waitUrl; }
    public void setWaitUrl(String waitUrl) { this.waitUrl = waitUrl; }
}