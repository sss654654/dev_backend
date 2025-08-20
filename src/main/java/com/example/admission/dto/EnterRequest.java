package com.example.admission.dto;

public class EnterRequest {

    // 범용성을 위해 type과 id 필드 추가
    private String type;
    private String id;

    // 기존 필드 유지
    private String requestId;
    private String sessionId;
    private String movieId; // 하위 호환성을 위해 유지할 수 있음

    // --- Getters and Setters ---
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMovieId() { return movieId; }
    public void setMovieId(String movieId) { this.movieId = movieId; }
}