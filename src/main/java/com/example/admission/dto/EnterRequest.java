package com.example.admission.dto;

public class EnterRequest {

    private String requestId;
    private String sessionId;
    private String movieId;

    // --- Getters and Setters ---
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMovieId() { return movieId; }
    public void setMovieId(String movieId) { this.movieId = movieId; }
}