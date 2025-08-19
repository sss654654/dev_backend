package com.example.couponmanagement.dto;

import com.example.couponmanagement.domain.ClaimStatus;


public class RequestCouponCommand {
    private String requestId;
    private String couponId;
    private String userId;
    private ClaimStatus status;

    public RequestCouponCommand() {}

    public RequestCouponCommand(String requestId, String couponId, String userId, ClaimStatus status) {
        this.requestId = requestId;
        this.couponId = couponId;
        this.userId = userId;
        this.status = status;
    }
    public String getRequestId() { return requestId; }
    public String getCouponId() { return couponId; }
    public String getUserId() { return userId; }
    public ClaimStatus getStatus() { return status; }

    public void setRequestId(String requestId) { this.requestId = requestId; }
    public void setCouponId(String couponId) { this.couponId = couponId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setStatus(ClaimStatus status) { this.status = status; }

}
