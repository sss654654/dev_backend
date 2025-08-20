package com.example.admission.dto;

public class StatusResponse {
    private String type;
    private String id;
    private long activeSessions;
    private long waitingQueue;

    public StatusResponse(String type, String id, long activeSessions, long waitingQueue) {
        this.type = type;
        this.id = id;
        this.activeSessions = activeSessions;
        this.waitingQueue = waitingQueue;
    }

    // Getters and Setters
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getActiveSessions() { return activeSessions; }
    public void setActiveSessions(long activeSessions) { this.activeSessions = activeSessions; }
    public long getWaitingQueue() { return waitingQueue; }
    public void setWaitingQueue(long waitingQueue) { this.waitingQueue = waitingQueue; }
}