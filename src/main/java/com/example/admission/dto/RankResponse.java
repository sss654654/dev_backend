package com.example.admission.dto;

public class RankResponse {
    private String requestId;
    private long rank;

    public RankResponse(String requestId, long rank) {
        this.requestId = requestId;
        this.rank = rank;
    }

    // Getters and Setters
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public long getRank() { return rank; }
    public void setRank(long rank) { this.rank = rank; }
}