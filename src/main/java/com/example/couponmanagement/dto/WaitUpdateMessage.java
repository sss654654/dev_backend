package com.example.couponmanagement.dto;


public class WaitUpdateMessage {
    private String requestId;
    private int position;     // 0이면 완료
    private String status;    // PENDING | COMPLETE
    private long ts;

    public WaitUpdateMessage() {}
    public WaitUpdateMessage(String requestId, int position, String status, long ts) {
        this.requestId = requestId; this.position = position; this.status = status; this.ts = ts;
    }
    public String getRequestId() { return requestId; }
    public int getPosition() { return position; }
    public String getStatus() { return status; }
    public long getTs() { return ts; }
    public void setRequestId(String v){ this.requestId = v; }
    public void setPosition(int v){ this.position = v; }
    public void setStatus(String v){ this.status = v; }
    public void setTs(long v){ this.ts = v; }
}