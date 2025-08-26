package com.example.admission.dto;

public class LeaveRequest {
    private String movieId;
    private String sessionId;
    private String requestId;

    // 기본 생성자
    public LeaveRequest() {}

    // 생성자
    public LeaveRequest(String movieId, String sessionId, String requestId) {
        this.movieId = movieId;
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    // Getters and Setters
    public String getMovieId() {
        return movieId;
    }

    public void setMovieId(String movieId) {
        this.movieId = movieId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}