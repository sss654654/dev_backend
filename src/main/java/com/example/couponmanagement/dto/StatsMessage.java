package com.example.couponmanagement.dto;

public class StatsMessage {
    private String couponId;
    private long pending;
    private int remainingStock;
    private long ts;

    public StatsMessage() {}

    public StatsMessage(String couponId, long pending, int remainingStock, long ts) {
        this.couponId = couponId;
        this.pending = pending;
        this.remainingStock = remainingStock;
        this.ts = ts;
    }

    public String getCouponId() { return couponId; }
    public long getPending() { return pending; }
    public int getRemainingStock() { return remainingStock; }
    public long getTs() { return ts; }

    public void setCouponId(String couponId) { this.couponId = couponId; }
    public void setPending(long pending) { this.pending = pending; }
    public void setRemainingStock(int remainingStock) { this.remainingStock = remainingStock; }
    public void setTs(long ts) { this.ts = ts; }
}